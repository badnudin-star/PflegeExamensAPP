import SwiftUI

// Kept behind an explicit build flag until end-to-end tests, durable deletion
// journal rollout and real SideStore/Keychain testing have been completed.
enum RankedFeature {
    static let account = RankingAccount(storage: KeychainRankingStorage(), transport: HTTPSRankingTransport())
    @MainActor static func model() -> RankedParticipationModel {
        RankedParticipationModel(account: account, loader: HTTPSRankedQuizFetcher())
    }
}
struct RankedParticipationView: View {
    @StateObject private var model: RankedParticipationModel
    @State private var name = ""
    @State private var code = ""
    @State private var visibleCode: String?
    @State private var recoveryMessage = ""
    @State private var showRecovery = false
    @State private var confirmRegister = false
    @State private var confirmDelete = false
    @State private var confirmSubmit = false
    @State private var confirmClose = false
    @State private var confirmCode = false
    @Environment(\.scenePhase) private var scenePhase
    init(model: RankedParticipationModel) { _model = StateObject(wrappedValue: model) }
    var body: some View {
        ScrollView { VStack(alignment: .leading, spacing: 16) {
            Text("Freiwilliges Wertungsquiz").font(.title2.bold())
            Text("Die Rangliste ist ein Spiel, keine Prüfung. Lösungen sind sichtbar und mehrere Konten möglich. Lokales Üben zählt nicht zur Rangliste.").font(.footnote)
            Text(accountText)
            if model.busy { ProgressView("Bitte warten …") }
            Button("Kontostatus prüfen") { Task { await model.checkAccount() } }.buttonStyle(.bordered).disabled(model.busy)
            if canRegister {
                TextField("Pseudonym", text: $name).accessibilityIdentifier("ranking.name").textFieldStyle(.roundedBorder).autocorrectionDisabled()
                Button("Für Rangliste anmelden") { confirmRegister = true }.accessibilityIdentifier("ranking.register").buttonStyle(.borderedProminent)
                    .disabled(model.busy || !validRankingName(name))
            }
            Button(showRecovery ? "Zugangsoptionen ausblenden" : "Zugangsoptionen") { showRecovery.toggle(); visibleCode = nil; code = "" }.disabled(model.busy)
            if showRecovery {
                if case .some(.registered(_)) = model.accountState {
                    Button("Sicherungscode anzeigen") { confirmCode = true }.disabled(model.busy)
                    if let secret = visibleCode {
                        Text("Geheim halten. Wer diesen Code kennt, kann das Konto benutzen oder löschen. Screenshots und Bildschirmaufnahmen können ihn enthalten.").font(.footnote)
                        Text(secret).textSelection(.enabled).privacySensitive()
                        Button("Code ausblenden") { visibleCode = nil }
                    }
                }
                if canRegister {
                    SecureField("Geheimer Sicherungscode", text: $code).textFieldStyle(.roundedBorder).autocorrectionDisabled().textInputAutocapitalization(.never).privacySensitive()
                    Button("Ranglistenzugang wiederherstellen") {
                        Task {
                            let result = await model.restoreAccount(code); code = ""
                            switch result {
                            case .restored: recoveryMessage = "Ranglistenzugang bestätigt und gespeichert."
                            case .invalidCode: recoveryMessage = "Ungültiges Codeformat."
                            case .existingDifferentAccount: recoveryMessage = "Ein anderer Zugang besteht bereits und wurde nicht überschrieben."
                            case .pendingAccountMismatch: recoveryMessage = "Eine offene Abgabe gehört zu einem anderen Zugang."
                            default: recoveryMessage = "Der Zugang konnte nicht sicher bestätigt werden."
                            }
                        }
                    }.disabled(model.busy || code.isEmpty)
                }
                Text("Der Code ist ein Zugangsschlüssel, kein Lernfortschritts-Backup. Niemals im Chat teilen. Offene Abgaben werden nicht auf ein anderes Gerät übertragen; denselben Zugang nicht gleichzeitig auf mehreren Geräten verwenden.").font(.footnote)
                if !recoveryMessage.isEmpty { Text(recoveryMessage).font(.footnote) }
            }
            if canDelete {
                Button(model.accountState == .some(.deletionPending) ? "Kontolöschung fortsetzen" : "Ranglistenkonto löschen", role: .destructive) { confirmDelete = true }.accessibilityIdentifier("ranking.delete").disabled(model.busy)
            }
            if case .some(.registered(_)) = model.accountState {
                Button("Heutiges Wertungsquiz laden") { Task { await model.loadQuiz() } }.accessibilityIdentifier("ranking.load").buttonStyle(.borderedProminent).disabled(model.busy)
                Text("Antworten bleiben während dieser Sitzung im Speicher und können beim Verlassen verloren gehen. Für die Wertung werden sie nach deiner Bestätigung an den Server gesendet.").font(.footnote)
            }
            if !model.message.isEmpty { Text(model.message) }
            if let quiz = model.quiz {
                if model.index < quiz.questions.count {
                    let question = quiz.questions[model.index]
                    Text("\(model.index + 1) / \(quiz.questions.count) · \(question.topic)").font(.footnote)
                    Text(question.question).font(.headline)
                    ForEach(Array(question.options.enumerated()), id: \.offset) { index, option in
                        Button(option) { model.choose(index) }.accessibilityIdentifier("ranking.option.\(index)").buttonStyle(.bordered)
                            .disabled(model.busy || model.answers.count > model.index)
                    }
                    if model.answers.count > model.index {
                        let selected = model.answers[model.index]
                        Text(selected == question.correctIndex ? "Richtig" : "Nicht richtig. Richtig ist: \(question.options[question.correctIndex])").bold()
                        Text(question.explanation)
                        Button("Nächste Frage") { model.advance() }.accessibilityIdentifier("ranking.next").buttonStyle(.borderedProminent).disabled(model.busy)
                    }
                } else {
                    Text("Alle Fragen beantwortet. Die Wertung erfolgt erst nach ausdrücklicher Abgabe durch den Server.")
                    Button("Gewertet abgeben") { confirmSubmit = true }.accessibilityIdentifier("ranking.submit").buttonStyle(.borderedProminent).disabled(!model.readyToSubmit)
                }
            }
            Divider()
            Text("Ungeklärte Quizabgabe").font(.headline)
            Text("Ein fehlendes Ergebnis beweist nicht, dass eine frühere Abgabe nicht noch ankommt.").font(.footnote)
            if let status = model.attemptState { Text(attemptText(status)) }
            Button("Abgabestatus prüfen") { Task { await model.checkAttempt() } }.accessibilityIdentifier("ranking.checkAttempt").buttonStyle(.bordered).disabled(model.busy)
            if case .some(.recorded(_, _)) = model.attemptState {
                Button("Ergebnis bestätigen") { Task { await model.acknowledge() } }.accessibilityIdentifier("ranking.ack").buttonStyle(.borderedProminent).disabled(model.busy)
            }
            if case .some(.uncertain(_)) = model.attemptState {
                Button("Auf diesen Wertungstag verzichten") { confirmClose = true }.disabled(model.busy)
            }
        }.frame(maxWidth: .infinity, alignment: .leading).padding() }
        .navigationTitle("Wertungsquiz").navigationBarTitleDisplayMode(.inline)
        .task { await model.checkAccount() }
        .onChange(of: scenePhase) { phase in if phase != .active { code = ""; visibleCode = nil } }
        .confirmationDialog("Für die Rangliste anmelden?", isPresented: $confirmRegister, titleVisibility: .visible) {
            Button("Anmeldung senden") { Task { await model.register(name) } }
        } message: { Text("Pseudonym und spätere Punkte können öffentlich erscheinen. Der Dienst verarbeitet Verbindungsdaten. Verwende keine personenbezogenen Angaben. Bei Löschung bleibt ein Hash als Sperre; Daten können in Sicherungen oder Kopien verbleiben.") }
        .confirmationDialog("Ranglistenkonto löschen?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Konto löschen", role: .destructive) { Task { await model.deleteAccount() } }
        } message: { Text("Pseudonym und Wertungen werden entfernt. Eine Hash-Sperre bleibt; Daten können in Sicherungen oder Kopien verbleiben. Bei unklarer Antwort muss die Löschung erneut geprüft werden. Lokale Übungen bleiben erhalten.") }
        .confirmationDialog("Quiz gewertet abgeben?", isPresented: $confirmSubmit, titleVisibility: .visible) {
            Button("Einmalig abgeben") { Task { await model.submit() } }
        } message: { Text("Deine 50 Antworten werden übertragen. Pro Konto und Tag ist nur ein Wertungsversuch möglich. Nach einer Unterbrechung den Status prüfen, nicht blind erneut abgeben.") }
        .confirmationDialog("Wertungstag sperren?", isPresented: $confirmClose, titleVisibility: .visible) {
            Button("Wertungstag unwiderruflich sperren", role: .destructive) { Task { await model.closePending() } }
        } message: { Text("Wenn kein Ergebnis gespeichert wurde, wird der Tag für dieses Konto gesperrt; auch eine verspätete Abgabe zählt dann nicht. Lokales Üben bleibt möglich.") }
        .confirmationDialog("Sicherungscode schützen", isPresented: $confirmCode, titleVisibility: .visible) {
            Button("Code anzeigen") { Task { if case .ready(let value) = await model.recoveryCode() { visibleCode = value } else { recoveryMessage = "Code nicht verfügbar." } } }
        } message: { Text("Der Code ist dein vollständiger Ranglistenzugang. Niemals teilen oder in einem Chat eingeben. Sicher und getrennt vom Gerät aufbewahren.") }
    }
    private var canRegister: Bool {
        switch model.accountState {
        case .some(.notPrepared), .some(.notRegistered), .some(.uncertain), .some(.invalidName), .some(.nameConflict): return true
        default: return false
        }
    }
    private var canDelete: Bool {
        if case .some(.registered(_)) = model.accountState { return true }
        return model.accountState == .some(.deletionPending)
    }
    private var accountText: String {
        switch model.accountState {
        case .none: return "Ranglistenzugang wird geprüft …"
        case .some(.registered(let name)): return "Angemeldet als \(name)."
        case .some(.notPrepared), .some(.noAccount), .some(.deleted): return "Kein Ranglistenzugang auf diesem Gerät."
        case .some(.notRegistered): return "Kein Konto gefunden. Eine frühere Anmeldung kann noch eintreffen; nur mit demselben Pseudonym erneut versuchen."
        case .some(.invalidName): return "Pseudonym ungültig."
        case .some(.nameConflict): return "Der Zugang ist mit einem anderen Pseudonym registriert."
        case .some(.storageUnavailable): return "Zugangsspeicher nicht verfügbar; nichts wurde überschrieben."
        case .some(.deletionPending): return "Eine Kontolöschung ist offen; Anmeldung und Abgabe sind gesperrt."
        case .some(.busy), .some(.uncertain): return "Status unklar. Bitte prüfen; bei erneuter Anmeldung dasselbe Pseudonym verwenden."
        }
    }
    private func attemptText(_ state: RankedAttemptState) -> String {
        switch state {
        case .none: return "Keine ausstehende Abgabe auf diesem Gerät."
        case .recorded(let date, let score): return "Bestätigtes Ergebnis vom \(date): \(score) von 50 richtig."
        case .uncertain(let date): return "Abgabe vom \(date): Ergebnis oder Sperre noch nicht bestätigt."
        case .differentAttempt(let date): return "Das Ergebnis vom \(date) kann dieser Abgabe nicht sicher zugeordnet werden."
        case .closed(let date): return "Wertungstag \(date) wurde gesperrt."
        case .deletionPending: return "Kontolöschung offen."
        default: return "Abgabestatus oder Zugangsspeicher ist nicht verfügbar."
        }
    }
}
