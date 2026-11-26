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
enum RankingAccountOperation { case status, register(String), delete }
protocol RankingAccountTransport {
    // A production transport must use a fixed HTTPS origin, forbid redirects and retries,
    // cap all responses and never log or store the Authorization header.
    func send(_ operation: RankingAccountOperation, token: String) async throws -> RankingAccountReply
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
