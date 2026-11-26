import Foundation

// Test-only loopback bridge: the live client itself has no configurable origin.
// It transfers the exact request bytes produced by the fixed-origin request builder.
struct FixtureRankingTransport: RankingAccountTransport {
    let port: Int
    let discardFirstSubmitResponse: Bool
    let tracker: FixtureSubmitTracker
    func send(_ operation: RankingAccountOperation, token: String) async throws -> RankingAccountReply {
        let original = try HTTPSRankingTransport.request(operation, token: token)
        guard let source = URLComponents(url: original.url!, resolvingAgainstBaseURL: false),
              source.scheme == "https", source.host == "pflege-examen-ranking.badnudin.workers.dev" else { throw SecureRankingError.invalid }
        var local = URLComponents(); local.scheme = "http"; local.host = "127.0.0.1"; local.port = port
        local.percentEncodedPath = source.percentEncodedPath; local.percentEncodedQuery = source.percentEncodedQuery
        var request = original; request.url = local.url
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, data.count <= 4096 else { throw SecureRankingError.invalid }
        if case .submit = operation, discardFirstSubmitResponse, await tracker.discardOnce() { throw SecureRankingError.unavailable }
        return RankingAccountReply(status: http.statusCode, body: data, deletionReceipt: http.value(forHTTPHeaderField: "X-Quiz-Deletion-Receipt"))
    }
}
actor FixtureSubmitTracker {
    private var didDiscard = false
    func discardOnce() -> Bool { if didDiscard { return false }; didDiscard = true; return true }
}
struct FixtureRankingFetcher: RankedQuizFetching {
    let port: Int
    func load() async throws -> RankedQuizPayload {
        let (data, response) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:\(port)/quiz/today")!)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw SecureRankingError.unavailable }
        return try RankedQuizPayload.decode(data)
    }
}
struct RankingWorkerSmoke {
    @MainActor static func run(port: Int) async throws {
        guard (1...65535).contains(port) else { throw SecureRankingError.invalid }
        let storage = FakeRankingStorage(), tracker = FixtureSubmitTracker()
        let account = RankingAccount(storage: storage, transport: FixtureRankingTransport(port: port, discardFirstSubmitResponse: true, tracker: tracker),
                                     tokenGenerator: { String(repeating: "ab", count: 32) })
        let model = RankedParticipationModel(account: account, loader: FixtureRankingFetcher(port: port))
        await model.checkAccount(); precondition(model.accountState == .some(.notPrepared))
        await model.register("iOS Fixture"); precondition(model.accountState == .some(.registered("iOS Fixture")))
        await model.loadQuiz(); guard let quiz = model.quiz else { fatalError("Synthetisches Tagesquiz nicht verfügbar") }
        for question in quiz.questions { model.choose(question.correctIndex); model.advance() }
        precondition(model.readyToSubmit)
        await model.submit(); precondition(model.attemptState == .some(.uncertain(quiz.date)))
        await model.checkAttempt(); precondition(model.attemptState == .some(.recorded(quiz.date, 50)))
        await model.acknowledge(); precondition(model.attemptState == .some(.none))
        await model.loadQuiz(); precondition(model.quiz == nil && model.message.contains("50 von 50"))
        await model.deleteAccount(); precondition(model.accountState == .some(.notPrepared) && storage.data.isEmpty)
        print("Swift iOS synthetic Worker flow passed")
    }
}
