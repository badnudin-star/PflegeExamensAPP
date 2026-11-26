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
        print("Ranglistenkonto-Grundlage: Speicherung vor Versand, Wiederholung, Löschquittung und Fehlerfälle synthetisch geprüft; nicht aktiviert")
    }
}
