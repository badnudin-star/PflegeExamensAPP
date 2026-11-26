#if RANKING_UI_TEST
import Foundation

// Compiled ONLY into a dedicated simulator UI-test build, never the release IPA.
// It contains no real accounts or network requests.
final class SyntheticUIStorage: RankingSecureStorage {
    private var entries: [SecureRankingKey: Data] = [:]
    func read(_ key: SecureRankingKey) throws -> Data? { entries[key] }
    func add(_ key: SecureRankingKey, _ value: Data) throws -> Bool {
        guard entries[key] == nil else { return false }; entries[key] = value; return true
    }
    func replace(_ key: SecureRankingKey, expected: Data, value: Data) throws -> Bool {
        guard entries[key] == expected else { return false }; entries[key] = value; return true
    }
    func remove(_ key: SecureRankingKey, expected: Data) throws -> Bool {
        guard entries[key] == expected else { return false }; entries.removeValue(forKey: key); return true
    }
}
actor SyntheticUITransport: RankingAccountTransport {
    private var name: String?
    private var result: (date: String, correct: Int, hash: String)?
    func send(_ operation: RankingAccountOperation, token: String) async throws -> RankingAccountReply {
        func reply(_ status: Int, _ value: [String: Any]? = nil, receipt: String? = nil) throws -> RankingAccountReply {
            RankingAccountReply(status: status, body: try value.map { try JSONSerialization.data(withJSONObject: $0) } ?? Data(), deletionReceipt: receipt)
        }
        switch operation {
        case .status:
            guard let name = name else { return try reply(404) }
            return try reply(200, ["username": name])
        case .register(let candidate):
            if let current = name, current != candidate { return try reply(409) }
            name = candidate; return try reply(201, ["username": candidate])
        case .delete:
            name = nil; result = nil; return try reply(204, receipt: "v1")
        case .lookup(let date):
            if let result = result, result.date == date {
                return try reply(200, ["date": date, "correct": result.correct, "total": 50, "payloadHash": result.hash])
            }
            return try reply(404, ["date": date, "error": "Kein Versuch"])
        case .submit(let raw):
            guard let json = try JSONSerialization.jsonObject(with: raw) as? [String: Any], let date = json["date"] as? String,
                  let answers = json["answers"] as? [[String: Any]], answers.count == 50 else { return try reply(400) }
            result = (date, 50, rankingHash(raw)); return try reply(201, ["date": date, "correct": 50, "total": 50])
        case .close(let date): return try reply(200, ["date": date, "status": "closed"])
        }
    }
}
struct SyntheticUIQuiz: RankedQuizFetching {
    func load() async throws -> RankedQuizPayload {
        let questions = (0..<50).map { i in RankedQuizQuestion(id: "fixture-\(i)", topic: "Nur UI-Test", question: "Testfrage \(i + 1)",
            options: ["Antwort A", "Antwort B", "Antwort C", "Antwort D"], correctIndex: 0, explanation: "Nur synthetischer Test.") }
        return RankedQuizPayload(date: "2026-01-01", fingerprint: String(repeating: "a", count: 64), questions: questions)
    }
}
@MainActor
enum SyntheticRankingUI {
    static func model() -> RankedParticipationModel {
        let account = RankingAccount(storage: SyntheticUIStorage(), transport: SyntheticUITransport(), tokenGenerator: { String(repeating: "b", count: 64) })
        return RankedParticipationModel(account: account, loader: SyntheticUIQuiz())
    }
}
#endif
