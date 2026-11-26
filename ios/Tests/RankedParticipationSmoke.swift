import Foundation

// End-to-end state flow with synthetic account, quiz and replies; no real credentials.
struct RankedParticipationSmoke {
    @MainActor static func run() async throws {
        let questions = (0..<50).map { i in
            RankedQuizQuestion(id: "question-\(i)", topic: "Thema", question: "Frage \(i)",
                               options: ["A", "B", "C", "D"], correctIndex: i % 4, explanation: "Begründung")
        }
        let quiz = RankedQuizPayload(date: "2026-01-01", fingerprint: String(repeating: "b", count: 64), questions: questions)
        let storage = FakeRankingStorage(), transport = FakeRankingTransport()
        let secret = String(repeating: "a", count: 64)
        let account = RankingAccount(storage: storage, transport: transport, tokenGenerator: { secret })
        let model = RankedParticipationModel(account: account, loader: SyntheticRankedQuizFetcher(quiz: quiz))
        await model.checkAccount(); precondition(model.accountState == .some(.notPrepared))
        transport.replies.append(RankingAccountReply(status: 201, body: Data(#"{"username":"Test"}"#.utf8), deletionReceipt: nil))
        await model.register("Test"); precondition(model.accountState == .some(.registered("Test")))
        transport.replies.append(RankingAccountReply(status: 404, body: Data(#"{"date":"2026-01-01","error":"noch kein Versuch"}"#.utf8), deletionReceipt: nil))
        await model.loadQuiz(); precondition(model.quiz?.questions.count == 50 && model.index == 0)
        await model.submit(); precondition(!model.readyToSubmit)
        model.choose(0); model.choose(2); precondition(model.answers == [0])
        model.advance(); model.advance(); precondition(model.index == 1)
        for index in 1..<50 { model.choose(index % 4); model.advance() }
        precondition(model.readyToSubmit && model.answers.count == 50 && model.index == 50)
        // A transport interruption keeps the pending marker and forbids a second submit.
        await model.submit(); precondition(model.attemptState == .some(.uncertain("2026-01-01")) && !model.readyToSubmit)
        let calls = transport.calls.count
        await model.submit(); precondition(transport.calls.count == calls)
        let marker = try JSONDecoder().decode(RankingPendingMarker.self, from: storage.data[.pending]!)
        let reply = "{\"date\":\"2026-01-01\",\"correct\":50,\"total\":50,\"payloadHash\":\"" + marker.payloadHash + "\"}"
        transport.replies.append(RankingAccountReply(status: 200, body: Data(reply.utf8), deletionReceipt: nil))
        await model.checkAttempt(); precondition(model.attemptState == .some(.recorded("2026-01-01", 50)))
        await model.acknowledge(); precondition(model.attemptState == .some(.none) && model.quiz == nil && storage.data[.pending] == nil)
        transport.replies.append(RankingAccountReply(status: 204, body: Data(), deletionReceipt: "v1"))
        await model.deleteAccount(); precondition(model.accountState == .some(.notPrepared) && storage.data.isEmpty)
        print("iOS-Ranglistenablauf: Anmeldung, Quiz, unklare Abgabe, Wiederabgleich und Löschung synthetisch geprüft")
    }
}
