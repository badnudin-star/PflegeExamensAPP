import Foundation
import Combine

// A single shared account actor must back all ranking screens. This model keeps
// answers in memory only and is never instantiated by the normal build yet.
@MainActor
final class RankedParticipationModel: ObservableObject {
    private let account: RankingAccount
    private let loader: RankedQuizFetching
    @Published private(set) var accountState: RankingAccountState?
    @Published private(set) var attemptState: RankedAttemptState?
    @Published private(set) var quiz: RankedQuizPayload?
    @Published private(set) var answers: [Int] = []
    @Published private(set) var index = 0
    @Published private(set) var busy = false
    @Published private(set) var message = ""
    init(account: RankingAccount, loader: RankedQuizFetching) { self.account = account; self.loader = loader }
    var readyToSubmit: Bool {
        guard let quiz = quiz else { return false }
        return !busy && attemptState == nil && index == quiz.questions.count && answers.count == quiz.questions.count
    }
    func checkAccount() async {
        guard !busy else { return }; busy = true; defer { busy = false }
        accountState = await account.status()
    }
    func register(_ name: String) async {
        guard !busy else { return }; busy = true; defer { busy = false }
        accountState = await account.register(name)
    }
    func loadQuiz() async {
        guard !busy, case .some(.registered(_)) = accountState else { return }
        busy = true; defer { busy = false }; message = ""
        switch await account.startQuiz(loader: loader) {
        case .ready(let next):
            if quiz?.date != next.date || quiz?.fingerprint != next.fingerprint { answers = []; index = 0 }
            quiz = next; attemptState = nil
        case .recorded(let date, let score): quiz = nil; message = "Für \(date) ist bereits ein Ergebnis gespeichert: \(score) von 50 richtig."
        case .closed(let date): quiz = nil; message = "Der Wertungstag \(date) ist für dieses Konto gesperrt."
        case .pending: quiz = nil; message = "Eine Abgabe ist ungeklärt oder noch nicht bestätigt. Bitte zuerst den Abgabestatus prüfen."
        case .deletionPending: quiz = nil; accountState = .deletionPending
        case .busy, .unavailable: quiz = nil; message = "Wertungsquiz oder Ergebnisstatus derzeit nicht verfügbar."
        }
    }
    func choose(_ choice: Int) {
        guard !busy, let quiz = quiz, index < quiz.questions.count, answers.count == index, (0...3).contains(choice) else { return }
        answers.append(choice)
    }
    func advance() {
        guard !busy, let quiz = quiz, index < quiz.questions.count, answers.count == index + 1 else { return }
        index += 1
    }
    func submit() async {
        guard readyToSubmit, let quiz = quiz else { return }
        busy = true; defer { busy = false }
        let submissions = zip(quiz.questions, answers).map { RankedAnswer(id: $0.0.id, option: $0.1) }
        attemptState = await account.submit(date: quiz.date, fingerprint: quiz.fingerprint, answers: submissions)
    }
    func checkAttempt() async {
        guard !busy else { return }; busy = true; defer { busy = false }
        attemptState = await account.reconcile()
    }
    func acknowledge() async {
        guard !busy else { return }; busy = true; defer { busy = false }
        attemptState = await account.acknowledge()
        if attemptState == .some(.none) { quiz = nil; answers = []; index = 0; message = "Ergebnis bestätigt." }
    }
    func closePending() async {
        guard !busy else { return }; busy = true; defer { busy = false }
        attemptState = await account.closePending()
        if case .some(.closed(_)) = attemptState { quiz = nil; answers = []; index = 0 }
    }
    func deleteAccount() async {
        guard !busy else { return }; busy = true; defer { busy = false }
        let result = await account.delete()
        switch result {
        case .deleted, .noAccount:
            accountState = .notPrepared; quiz = nil; answers = []; index = 0; attemptState = nil
            message = result == .deleted ? "Die Löschung wurde bestätigt und der lokale Zugang entfernt." : "Kein lokaler Zugang vorhanden."
        case .uncertain, .storageUnavailable:
            accountState = .deletionPending; quiz = nil
            message = "Löschung oder lokale Bereinigung nicht bestätigt. Bitte erneut versuchen."
        default: accountState = result
        }
    }
    func recoveryCode() async -> RankingRecoveryState {
        guard !busy else { return .busy }; busy = true; defer { busy = false }
        return await account.recoveryCode()
    }
    func restoreAccount(_ code: String) async -> RankingRecoveryState {
        guard !busy else { return .busy }; busy = true; defer { busy = false }
        let result = await account.restoreAccount(code)
        if case .restored(let name) = result { accountState = .registered(name) }
        return result
    }
}
