import Foundation
import Security
import CryptoKit

// This account subsystem is not connected to the UI or the production server yet.
// All instances that touch the same storage MUST share one RankingAccount actor.
enum SecureRankingKey: String { case token = "token-v1", deletion = "deletion-v1", pending = "pending-v1" }
enum SecureRankingError: Error { case unavailable, invalid }
protocol RankingSecureStorage {
    func read(_ key: SecureRankingKey) throws -> Data?
    func add(_ key: SecureRankingKey, _ value: Data) throws -> Bool
    func replace(_ key: SecureRankingKey, expected: Data, value: Data) throws -> Bool
    func remove(_ key: SecureRankingKey, expected: Data) throws -> Bool
}

// Device-only, non-synchronizing Keychain entries. Never migrate the bearer token
// through an iCloud backup; a separate, explicit recovery flow is still needed.
final class KeychainRankingStorage: RankingSecureStorage {
    private let service = "de.pflegeexamen.ios.ranking.v1"
    private func query(_ key: SecureRankingKey) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
         kSecAttrAccount as String: key.rawValue, kSecAttrSynchronizable as String: false]
    }
    func read(_ key: SecureRankingKey) throws -> Data? {
        var q = query(key); q[kSecReturnData as String] = true; q[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data, data.count <= 2048 else { throw SecureRankingError.unavailable }
        return data
    }
    func add(_ key: SecureRankingKey, _ value: Data) throws -> Bool {
        guard value.count <= 2048 else { throw SecureRankingError.invalid }
        var q = query(key); q[kSecValueData as String] = value
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(q as CFDictionary, nil)
        if status == errSecDuplicateItem { return false }
        guard status == errSecSuccess, try read(key) == value else { throw SecureRankingError.unavailable }
        return true
    }
    func replace(_ key: SecureRankingKey, expected: Data, value: Data) throws -> Bool {
        guard value.count <= 2048 else { throw SecureRankingError.invalid }
        guard try read(key) == expected else { return false }
        let status = SecItemUpdate(query(key) as CFDictionary, [kSecValueData as String: value] as CFDictionary)
        guard status == errSecSuccess, try read(key) == value else { throw SecureRankingError.unavailable }
        return true
    }
    func remove(_ key: SecureRankingKey, expected: Data) throws -> Bool {
        guard try read(key) == expected else { return false }
        let status = SecItemDelete(query(key) as CFDictionary)
        guard status == errSecSuccess, try read(key) == nil else { throw SecureRankingError.unavailable }
        return true
    }
}

func validRankingHash(_ value: String) -> Bool {
    value.utf8.count == 64 && value.utf8.allSatisfy { (48...57).contains($0) || (97...102).contains($0) }
}
func rankingHash(_ value: Data) -> String { SHA256.hash(data: value).map { String(format: "%02x", $0) }.joined() }
func validRankingName(_ value: String) -> Bool {
    guard (2...24).contains(value.utf16.count), value == value.trimmingCharacters(in: .whitespacesAndNewlines),
          !value.contains("  ") else { return false }
    return value.range(of: #"^[\p{L}\p{N}_ .-]+$"#, options: .regularExpression) != nil
}
func newRankingToken() throws -> String {
    var bytes = [UInt8](repeating: 0, count: 32)
    guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { throw SecureRankingError.unavailable }
    defer { bytes = [UInt8](repeating: 0, count: 32) }
    return bytes.map { String(format: "%02x", $0) }.joined()
}
struct RankingDeletionMarker: Codable, Equatable {
    let version: Int
    let accountHash: String
    let confirmed: Bool
    func validate() throws {
        guard version == 1, validRankingHash(accountHash) else { throw SecureRankingError.invalid }
    }
}
struct RankingPendingMarker: Codable, Equatable {
    let version: Int
    let date: String
    let quizHash: String
    let accountHash: String
    let payloadHash: String
    let correct: Int?
    func validate() throws {
        guard version == 1, validArchiveDate(date), validRankingHash(quizHash), validRankingHash(accountHash),
              validRankingHash(payloadHash), correct == nil || (0...50).contains(correct!) else { throw SecureRankingError.invalid }
    }
}
struct RankingAccountReply {
    let status: Int
    let body: Data
    let deletionReceipt: String?
}
enum RankingAccountOperation {
    case status, register(String), delete
    case submit(Data), lookup(String), close(String)
}
protocol RankingAccountTransport {
    // A production transport must use a fixed HTTPS origin, forbid redirects and retries,
    // cap all responses and never log or store the Authorization header.
    func send(_ operation: RankingAccountOperation, token: String) async throws -> RankingAccountReply
}
struct RankedAnswer: Codable { let id: String; let option: Int }
private struct RankedSubmissionBody: Encodable { let date: String; let fingerprint: String; let answers: [RankedAnswer] }
private struct RankedScoreBody: Decodable { let date: String; let correct: Int?; let total: Int?; let payloadHash: String?; let status: String? }
enum RankedAttemptState: Equatable {
    case busy, deletionPending, storageUnavailable, noAccount, invalidInput, pending
    case uncertain(String), differentAttempt(String), recorded(String, Int), closed(String), none
}
// Account recovery is a bearer credential, not an export of local learning progress.
func normalizeRankingRecovery(_ input: String) -> String? {
    guard input.utf16.count <= 128 else { return nil }
    let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
    let patterns = [#"^[0-9a-fA-F]{64}$"#, #"^[0-9a-fA-F]{8}(?:[- ][0-9a-fA-F]{8}){7}$"#]
    guard patterns.contains(where: { text.range(of: $0, options: .regularExpression) != nil }) else { return nil }
    let normalized = text.replacingOccurrences(of: "-", with: "").replacingOccurrences(of: " ", with: "").lowercased()
    return validRankingHash(normalized) ? normalized : nil
}
enum RankingRecoveryState: Equatable, CustomStringConvertible, CustomDebugStringConvertible {
    case ready(String), restored(String), busy, deletionPending, noAccount, invalidCode
    case existingDifferentAccount, pendingAccountMismatch, storageUnavailable, unavailable
    // Prevent inadvertent disclosure in ordinary diagnostic printing.
    var description: String {
        switch self {
        case .ready: return "ready(<redacted>)"
        case .restored: return "restored(<redacted>)"
        case .busy: return "busy"
        case .deletionPending: return "deletionPending"
        case .noAccount: return "noAccount"
        case .invalidCode: return "invalidCode"
        case .existingDifferentAccount: return "existingDifferentAccount"
        case .pendingAccountMismatch: return "pendingAccountMismatch"
        case .storageUnavailable: return "storageUnavailable"
        case .unavailable: return "unavailable"
        }
    }
    var debugDescription: String { description }
}
enum RankingAccountState: Equatable {
    case notPrepared, notRegistered, registered(String), invalidName, nameConflict, busy
    case storageUnavailable, deletionPending, uncertain, deleted, noAccount
}

// Local-first and fail-closed: no network operation occurs until the credential
// or deletion marker is stored and read back. No automatic retry. Actor reentrancy
// is explicitly blocked while a network call is in flight.
actor RankingAccount {
    private let storage: RankingSecureStorage
    private let transport: RankingAccountTransport
    private let tokenGenerator: () throws -> String
    private var busy = false
    init(storage: RankingSecureStorage, transport: RankingAccountTransport, tokenGenerator: @escaping () throws -> String = newRankingToken) {
        self.storage = storage; self.transport = transport; self.tokenGenerator = tokenGenerator
    }
    private func token() throws -> String? {
        guard let data = try storage.read(.token) else { return nil }
        guard let token = String(data: data, encoding: .utf8), validRankingHash(token) else { throw SecureRankingError.invalid }
        return token
    }
    private func deletion() throws -> (RankingDeletionMarker, Data)? {
        guard let raw = try storage.read(.deletion) else { return nil }
        guard raw.count <= 1024 else { throw SecureRankingError.invalid }
        let value = try JSONDecoder().decode(RankingDeletionMarker.self, from: raw)
        try value.validate()
        return (value, raw)
    }
    private func pending() throws -> (RankingPendingMarker, Data)? {
        guard let raw = try storage.read(.pending) else { return nil }
        let value = try JSONDecoder().decode(RankingPendingMarker.self, from: raw)
        try value.validate()
        return (value, raw)
    }
    private func username(_ reply: RankingAccountReply) -> String? {
        struct Body: Decodable { let username: String }
        guard reply.body.count <= 4096, let body = try? JSONDecoder().decode(Body.self, from: reply.body), validRankingName(body.username) else { return nil }
        return body.username
    }
    func status() async -> RankingAccountState {
        guard !busy else { return .busy }; busy = true; defer { busy = false }
        let access: String
        do {
            if let _ = try deletion() { return .deletionPending }
            guard let current = try token() else { return .notPrepared }
            access = current
        } catch { return .storageUnavailable }
        do {
            let reply = try await transport.send(.status, token: access)
            if reply.status == 404 { return .notRegistered }
            if reply.status == 200, let name = username(reply) { return .registered(name) }
            return .uncertain
        } catch { return .uncertain }
    }
    func register(_ name: String) async -> RankingAccountState {
        guard !busy else { return .busy }; busy = true; defer { busy = false }
        guard validRankingName(name) else { return .invalidName }
        var access: String
        do {
            if let _ = try deletion() { return .deletionPending }
            if let existing = try token() { access = existing }
            else {
                let generated = try tokenGenerator()
                guard validRankingHash(generated) else { throw SecureRankingError.invalid }
                let data = Data(generated.utf8)
                let saved = try storage.add(.token, data)
                guard let reloaded = try token(), !saved || reloaded == generated else { throw SecureRankingError.unavailable }
                access = reloaded
            }
        } catch { return .storageUnavailable }
        do {
            let reply = try await transport.send(.register(name), token: access)
            if reply.status == 409 { return .nameConflict }
            if (reply.status == 200 || reply.status == 201), username(reply) == name { return .registered(name) }
            return .uncertain
        } catch { return .uncertain }
    }
    // Display only after explicit user action; never send a recovery code except
    // as the bearer header to the fixed account-status endpoint.
    func recoveryCode() -> RankingRecoveryState {
        guard !busy else { return .busy }; busy = true; defer { busy = false }
        do {
            if let _ = try deletion() { return .deletionPending }
            guard let current = try token() else { return .noAccount }
            let chunks = stride(from: 0, to: 64, by: 8).map { start in
                let from = current.index(current.startIndex, offsetBy: start)
                let to = current.index(from, offsetBy: 8)
                return String(current[from..<to])
            }
            return .ready(chunks.joined(separator: "-"))
        } catch { return .storageUnavailable }
    }
    func restoreAccount(_ code: String) async -> RankingRecoveryState {
        guard !busy else { return .busy }; busy = true; defer { busy = false }
        guard let candidate = normalizeRankingRecovery(code) else { return .invalidCode }
        do {
            if let _ = try deletion() { return .deletionPending }
            if let existing = try token(), existing != candidate { return .existingDifferentAccount }
            if let (marker, _) = try pending(), marker.accountHash != rankingHash(Data(candidate.utf8)) { return .pendingAccountMismatch }
        } catch { return .storageUnavailable }
        let reply: RankingAccountReply
        do { reply = try await transport.send(.status, token: candidate) }
        catch { return .unavailable }
        guard reply.status == 200, let name = username(reply) else { return .unavailable }
        do {
            if try token() == nil {
                _ = try storage.add(.token, Data(candidate.utf8))
            }
            guard try token() == candidate else { return .existingDifferentAccount }
            return .restored(name)
        } catch { return .storageUnavailable }
    }
    private func saveRecorded(_ marker: RankingPendingMarker, raw: Data, correct: Int) throws -> Bool {
        guard marker.correct == nil, (0...50).contains(correct) else { return false }
        let next = RankingPendingMarker(version: 1, date: marker.date, quizHash: marker.quizHash,
                                        accountHash: marker.accountHash, payloadHash: marker.payloadHash, correct: correct)
        let data = try JSONEncoder().encode(next)
        guard try storage.replace(.pending, expected: raw, value: data), let (readBack, _) = try pending() else { return false }
        return readBack == next
    }
    // Each explicit submission sends at most one request. A timeout, 401 or 409
    // keeps the marker: a delayed request may still be committed by the server.
    func submit(date: String, fingerprint: String, answers: [RankedAnswer]) async -> RankedAttemptState {
        guard !busy else { return .busy }; busy = true; defer { busy = false }
        let ids = answers.map { $0.id }
        guard validArchiveDate(date), validRankingHash(fingerprint), answers.count == 50,
              Set(ids).count == ids.count, answers.allSatisfy({
                  !$0.id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && $0.id.utf16.count <= 200 &&
                  $0.id.unicodeScalars.allSatisfy { !CharacterSet.controlCharacters.contains($0) } && (0...3).contains($0.option)
              }) else { return .invalidInput }
        let encoder = JSONEncoder(); encoder.outputFormatting = .sortedKeys
        guard let body = try? encoder.encode(RankedSubmissionBody(date: date, fingerprint: fingerprint, answers: answers)), body.count <= 64 * 1024 else { return .invalidInput }
        let marker: RankingPendingMarker, raw: Data, access: String
        do {
            if let _ = try deletion() { return .deletionPending }
            guard let current = try token() else { return .noAccount }
            if let _ = try pending() { return .pending }
            access = current
            marker = RankingPendingMarker(version: 1, date: date, quizHash: fingerprint, accountHash: rankingHash(Data(current.utf8)), payloadHash: rankingHash(body), correct: nil)
            raw = try encoder.encode(marker)
            guard try storage.add(.pending, raw), let (check, _) = try pending(), check == marker else { return .storageUnavailable }
        } catch { return .storageUnavailable }
        do {
            let reply = try await transport.send(.submit(body), token: access)
            guard reply.status == 201, reply.body.count <= 4096,
                  let score = try? JSONDecoder().decode(RankedScoreBody.self, from: reply.body), score.date == date,
                  score.total == 50, let correct = score.correct, (0...50).contains(correct),
                  try saveRecorded(marker, raw: raw, correct: correct) else { return .uncertain(date) }
            return .recorded(date, correct)
        } catch { return .uncertain(date) }
    }
    // Read-only reconciliation never repeats a submission or clears an unknown result.
    func reconcile() async -> RankedAttemptState {
        guard !busy else { return .busy }; busy = true; defer { busy = false }
        let marker: RankingPendingMarker, raw: Data, access: String
        do {
            if let _ = try deletion() { return .deletionPending }
            guard let (existing, encoded) = try pending() else { return .none }
            guard let current = try token(), rankingHash(Data(current.utf8)) == existing.accountHash else { return .storageUnavailable }
            marker = existing; raw = encoded; access = current
        } catch { return .storageUnavailable }
        if let score = marker.correct { return .recorded(marker.date, score) }
        do {
            let reply = try await transport.send(.lookup(marker.date), token: access)
            guard reply.status == 200, reply.body.count <= 4096,
                  let result = try? JSONDecoder().decode(RankedScoreBody.self, from: reply.body), result.date == marker.date,
                  result.total == 50, let correct = result.correct, (0...50).contains(correct) else { return .uncertain(marker.date) }
            guard result.payloadHash == marker.payloadHash else { return .differentAttempt(marker.date) }
            guard try saveRecorded(marker, raw: raw, correct: correct) else { return .storageUnavailable }
            return .recorded(marker.date, correct)
        } catch { return .uncertain(marker.date) }
    }
    // Only an explicitly acknowledged confirmed result can be cleared.
    func acknowledge() -> RankedAttemptState {
        guard !busy else { return .busy }; busy = true; defer { busy = false }
        do {
            if let _ = try deletion() { return .deletionPending }
            guard let (marker, raw) = try pending() else { return .none }
            guard let current = try token(), rankingHash(Data(current.utf8)) == marker.accountHash,
                  marker.correct != nil, try storage.remove(.pending, expected: raw) else { return .storageUnavailable }
            return .none
        } catch { return .storageUnavailable }
    }
    // Explicit, irreversible action: only a matching closure or verified recorded
    // payload allows the marker to progress; never interpret a timeout as closure.
    func closePending() async -> RankedAttemptState {
        guard !busy else { return .busy }; busy = true; defer { busy = false }
        let marker: RankingPendingMarker, raw: Data, access: String
        do {
            if let _ = try deletion() { return .deletionPending }
            guard let (existing, encoded) = try pending() else { return .none }
            guard let current = try token(), rankingHash(Data(current.utf8)) == existing.accountHash else { return .storageUnavailable }
            marker = existing; raw = encoded; access = current
        } catch { return .storageUnavailable }
        if let score = marker.correct { return .recorded(marker.date, score) }
        do {
            let reply = try await transport.send(.close(marker.date), token: access)
            guard reply.status == 200, reply.body.count <= 4096,
                  let result = try? JSONDecoder().decode(RankedScoreBody.self, from: reply.body), result.date == marker.date else { return .uncertain(marker.date) }
            if result.status == "closed", result.correct == nil, result.total == nil, result.payloadHash == nil {
                guard try storage.remove(.pending, expected: raw) else { return .storageUnavailable }
                return .closed(marker.date)
            }
            if result.status == "recorded", let correct = result.correct, (0...50).contains(correct), result.total == 50 {
                guard result.payloadHash == marker.payloadHash else { return .differentAttempt(marker.date) }
                guard try saveRecorded(marker, raw: raw, correct: correct) else { return .storageUnavailable }
                return .recorded(marker.date, correct)
            }
            return .uncertain(marker.date)
        } catch { return .uncertain(marker.date) }
    }
    // Called only after explicit confirmation; if no definitive receipt arrives,
    // preserve journal and token for explicit retry and block account activity.
    func delete() async -> RankingAccountState {
        guard !busy else { return .busy }; busy = true; defer { busy = false }
        do {
            var record: RankingDeletionMarker
            var raw: Data
            if let existing = try deletion() { (record, raw) = existing }
            else {
                guard let current = try token() else { return .noAccount }
                record = RankingDeletionMarker(version: 1, accountHash: rankingHash(Data(current.utf8)), confirmed: false)
                raw = try JSONEncoder().encode(record)
                guard try storage.add(.deletion, raw), try deletion()?.0 == record else { throw SecureRankingError.unavailable }
            }
            let existingToken = try token()
            if let current = existingToken, rankingHash(Data(current.utf8)) != record.accountHash { return .storageUnavailable }
            if !record.confirmed {
                guard let current = existingToken else { return .storageUnavailable }
                let reply: RankingAccountReply
                do { reply = try await transport.send(.delete, token: current) } catch { return .uncertain }
                guard reply.status == 204 && reply.deletionReceipt == "v1" else { return .uncertain }
                let next = RankingDeletionMarker(version: 1, accountHash: record.accountHash, confirmed: true)
                let encoded = try JSONEncoder().encode(next)
                guard try storage.replace(.deletion, expected: raw, value: encoded), try deletion()?.0 == next else { throw SecureRankingError.unavailable }
                record = next; raw = encoded
            }
            if let (marker, data) = try pending() {
                guard marker.accountHash == record.accountHash, try storage.remove(.pending, expected: data) else { return .storageUnavailable }
            }
            if let current = try token() {
                guard rankingHash(Data(current.utf8)) == record.accountHash,
                      try storage.remove(.token, expected: Data(current.utf8)) else { return .storageUnavailable }
            }
            guard try storage.remove(.deletion, expected: raw), try storage.read(.deletion) == nil else { return .storageUnavailable }
            return .deleted
        } catch { return .storageUnavailable }
    }
}
