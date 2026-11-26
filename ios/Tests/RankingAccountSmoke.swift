import Foundation

// Synthetic in-memory credentials and replies only; no production requests.
final class FakeRankingStorage: RankingSecureStorage {
    var data: [SecureRankingKey: Data] = [:]
    var failAdd = false
    var failRemoveKey: SecureRankingKey?
    func read(_ key: SecureRankingKey) throws -> Data? { data[key] }
    func add(_ key: SecureRankingKey, _ value: Data) throws -> Bool {
        if failAdd { throw SecureRankingError.unavailable }
        if data[key] != nil { return false }; data[key] = value; return true
    }
    func replace(_ key: SecureRankingKey, expected: Data, value: Data) throws -> Bool {
        guard data[key] == expected else { return false }; data[key] = value; return true
    }
    func remove(_ key: SecureRankingKey, expected: Data) throws -> Bool {
        if failRemoveKey == key { throw SecureRankingError.unavailable }
        guard data[key] == expected else { return false }; data.removeValue(forKey: key); return true
    }
}
final class FakeRankingTransport: RankingAccountTransport {
    var calls: [String] = []
    var replies: [RankingAccountReply] = []
    func send(_ operation: RankingAccountOperation, token: String) async throws -> RankingAccountReply {
        calls.append(token)
        guard !replies.isEmpty else { throw SecureRankingError.unavailable }
        return replies.removeFirst()
    }
}
struct RankingAccountSmoke {
    static func run() async throws {
        precondition(validRankingName("Anna Müller") && validRankingName("Test-12"))
        precondition(!validRankingName("a") && !validRankingName(" Test") && !validRankingName("a  b"))
        precondition(!validRankingName("abc\n") && !validRankingHash("../"))
        let secret = String(repeating: "a", count: 64)
        let request = try HTTPSRankingTransport.request(.lookup("2026-01-01"), token: secret)
        precondition(request.url?.absoluteString == "https://pflege-examen-ranking.badnudin.workers.dev/quiz/attempt/result?date=2026-01-01")
        precondition(request.httpMethod == "GET" && request.value(forHTTPHeaderField: "Authorization") == "Bearer " + secret && !(request.url?.absoluteString.contains(secret) ?? true))
        let deletionRequest = try HTTPSRankingTransport.request(.delete, token: secret)
        precondition(deletionRequest.httpMethod == "DELETE" && deletionRequest.url?.path == "/participants/me")
        do { _ = try HTTPSRankingTransport.request(.lookup("../bad"), token: secret); fatalError("Unsicheres Datum akzeptiert") } catch {}
        do { _ = try HTTPSRankingTransport.request(.delete, token: "not-a-token"); fatalError("Ungültiges Token akzeptiert") } catch {}
        do { _ = try HTTPSRankingTransport.request(.submit(Data(repeating: 0, count: 65537)), token: secret); fatalError("Zu große Abgabe akzeptiert") } catch {}
        let storage = FakeRankingStorage(), transport = FakeRankingTransport()
        let account = RankingAccount(storage: storage, transport: transport, tokenGenerator: { secret })
        var state = await account.status(); precondition(state == .notPrepared)
        storage.failAdd = true
        state = await account.register("Test"); precondition(state == .storageUnavailable && transport.calls.isEmpty)
        storage.failAdd = false
        state = await account.register("Test"); precondition(state == .uncertain && storage.data[.token] == Data(secret.utf8))
        transport.replies.append(RankingAccountReply(status: 201, body: Data(#"{"username":"Test"}"#.utf8), deletionReceipt: nil))
        state = await account.register("Test"); precondition(state == .registered("Test") && transport.calls.count == 2 && transport.calls.allSatisfy { $0 == secret })
        transport.replies.append(RankingAccountReply(status: 204, body: Data(), deletionReceipt: nil))
        state = await account.delete(); precondition(state == .uncertain && storage.data[.token] != nil && storage.data[.deletion] != nil)
        let count = transport.calls.count
        state = await account.register("Test"); precondition(state == .deletionPending && transport.calls.count == count)
        let marker = RankingPendingMarker(version: 1, date: "2026-01-01", quizHash: String(repeating: "b", count: 64), accountHash: rankingHash(Data(secret.utf8)), payloadHash: String(repeating: "c", count: 64), correct: nil)
        storage.data[.pending] = try JSONEncoder().encode(marker)
        transport.replies.append(RankingAccountReply(status: 204, body: Data(), deletionReceipt: "v1"))
        storage.failRemoveKey = .token
        state = await account.delete(); precondition(state == .storageUnavailable && storage.data[.deletion] != nil && storage.data[.token] != nil && storage.data[.pending] == nil)
        storage.failRemoveKey = nil
        let beforeRetry = transport.calls.count
        state = await account.delete(); precondition(state == .deleted && storage.data.isEmpty && transport.calls.count == beforeRetry)
        state = await account.delete(); precondition(state == .noAccount)
        storage.data[.deletion] = Data("broken".utf8)
        state = await account.status(); precondition(state == .storageUnavailable)
        state = await account.register("Test"); precondition(state == .storageUnavailable && transport.calls.count == beforeRetry)
        precondition(storage.data[.deletion] == Data("broken".utf8))
        let attemptStorage = FakeRankingStorage(), attemptTransport = FakeRankingTransport()
        attemptStorage.data[.token] = Data(secret.utf8)
        let attemptAccount = RankingAccount(storage: attemptStorage, transport: attemptTransport)
        let answers = (0..<50).map { RankedAnswer(id: "q\($0)", option: $0 % 4) }
        let fingerprint = String(repeating: "b", count: 64)
        var attempt = await attemptAccount.submit(date: "2026-02-30", fingerprint: fingerprint, answers: answers)
        precondition(attempt == .invalidInput && attemptTransport.calls.isEmpty)
        attemptStorage.failAdd = true
        attempt = await attemptAccount.submit(date: "2026-01-01", fingerprint: fingerprint, answers: answers)
        precondition(attempt == .storageUnavailable && attemptTransport.calls.isEmpty)
        attemptStorage.failAdd = false
        attempt = await attemptAccount.submit(date: "2026-01-01", fingerprint: fingerprint, answers: answers)
        precondition(attempt == .uncertain("2026-01-01") && attemptTransport.calls.count == 1)
        let unresolvedData = attemptStorage.data[.pending]!
        let pending = try JSONDecoder().decode(RankingPendingMarker.self, from: unresolvedData)
        precondition(pending.correct == nil && pending.quizHash == fingerprint)
        attempt = await attemptAccount.submit(date: "2026-01-01", fingerprint: fingerprint, answers: answers)
        precondition(attempt == .pending && attemptTransport.calls.count == 1)
        attempt = await attemptAccount.acknowledge(); precondition(attempt == .storageUnavailable && attemptStorage.data[.pending] == unresolvedData)
        attemptTransport.replies.append(RankingAccountReply(status: 404, body: Data(), deletionReceipt: nil))
        attempt = await attemptAccount.reconcile(); precondition(attempt == .uncertain("2026-01-01") && attemptStorage.data[.pending] == unresolvedData)
        let wrong = "{\"date\":\"2026-01-01\",\"correct\":42,\"total\":50,\"payloadHash\":\"" + String(repeating: "f", count: 64) + "\"}"
        attemptTransport.replies.append(RankingAccountReply(status: 200, body: Data(wrong.utf8), deletionReceipt: nil))
        attempt = await attemptAccount.reconcile(); precondition(attempt == .differentAttempt("2026-01-01") && attemptStorage.data[.pending] == unresolvedData)
        let right = "{\"date\":\"2026-01-01\",\"correct\":42,\"total\":50,\"payloadHash\":\"" + pending.payloadHash + "\"}"
        attemptTransport.replies.append(RankingAccountReply(status: 200, body: Data(right.utf8), deletionReceipt: nil))
        attempt = await attemptAccount.reconcile(); precondition(attempt == .recorded("2026-01-01", 42))
        let callsBefore = attemptTransport.calls.count
        attempt = await attemptAccount.reconcile(); precondition(attempt == .recorded("2026-01-01", 42) && attemptTransport.calls.count == callsBefore)
        attempt = await attemptAccount.acknowledge(); precondition(attempt == .none && attemptStorage.data[.pending] == nil)
        attempt = await attemptAccount.submit(date: "2026-01-01", fingerprint: fingerprint, answers: answers)
        precondition(attempt == .uncertain("2026-01-01"))
        attemptTransport.replies.append(RankingAccountReply(status: 200, body: Data(#"{"date":"2026-01-01","status":"closed"}"#.utf8), deletionReceipt: nil))
        attempt = await attemptAccount.closePending(); precondition(attempt == .closed("2026-01-01") && attemptStorage.data[.pending] == nil)
        print("Gewerteter Versuch: Marker vor Versand, Timeout, Wiederabgleich und explizites Schließen synthetisch geprüft; nicht aktiviert")
        print("Ranglistenkonto-Grundlage: Speicherung vor Versand, Wiederholung, Löschquittung und Fehlerfälle synthetisch geprüft; nicht aktiviert")
    }
}
