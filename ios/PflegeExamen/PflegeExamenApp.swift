import SwiftUI

@main
struct PflegeExamenApp: App {
    var body: some Scene { WindowGroup { RootView() } }
}

struct RootView: View {
    @StateObject private var store = CatalogStore(bundleURL: Bundle.main.url(forResource: "learning_content", withExtension: "json"))
    @StateObject private var progress = ProgressStore()
    @Environment(\.scenePhase) private var scenePhase
    var body: some View {
        Group {
            if let catalog = store.snapshot?.catalog {
                TabView {
                    NotesView(catalog: catalog).tabItem { Label("Lernzettel", systemImage: "doc.text") }
                    CardsView(catalog: catalog).tabItem { Label("Karten", systemImage: "rectangle.stack") }
                    CasesView(catalog: catalog).tabItem { Label("Fälle", systemImage: "person.text.rectangle") }
                    QuizView(catalog: catalog).tabItem { Label("Quiz", systemImage: "checkmark.circle") }
                    SettingsView(store: store).tabItem { Label("Mehr", systemImage: "gearshape") }
                }.environmentObject(progress)
            } else {
                Text("Der Offline-Katalog konnte nicht geladen werden. Bitte eine geprüfte Version verwenden.").padding()
            }
        }.task(id: scenePhase) {
            guard scenePhase == .active else { return }
            while !Task.isCancelled {
                store.refreshIfDue()
                do { try await Task.sleep(nanoseconds: 60_000_000_000) } catch { break }
            }
        }
    }
}

struct NotesView: View {
    let catalog: Catalog
    @EnvironmentObject private var progress: ProgressStore
    @State private var search = ""
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("iOS-Vorschau: Original-PDFs müssen zum aktuellen Text passen; die Rangliste ist nur lesbar.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Section("Themen") {
                    ForEach(catalog.notes.filter { search.isEmpty || $0.topic.localizedCaseInsensitiveContains(search) || $0.detailed.localizedCaseInsensitiveContains(search) || $0.short.localizedCaseInsensitiveContains(search) }) { note in
                        NavigationLink {
                            NoteView(note: note)
                        } label: {
                            HStack { Text(note.topic); Spacer(); if progress.readNotes.contains(note.topic) { Image(systemName: "checkmark.circle.fill").accessibilityLabel("Gelesen") } }
                        }
                    }
                }
            }.navigationTitle("Lernzettel").searchable(text: $search, prompt: "Thema oder Lernzetteltext suchen")
        }
    }
}
struct NoteView: View {
    let note: Note
    @EnvironmentObject private var progress: ProgressStore
    @State private var simple = false
    @State private var showPDF = false
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Picker("Version", selection: $simple) {
                    Text("Neu").tag(false); Text("Einfach").tag(true)
                }.pickerStyle(.segmented)
                Button(progress.readNotes.contains(note.topic) ? "Als ungelesen markieren" : "Als gelesen markieren") {
                    progress.toggleRead(note.topic)
                }.buttonStyle(.bordered)
                if note.sources.indices.contains(simple ? 1 : 0),
                   NotePDFLocator.fileName(note.sources[simple ? 1 : 0]) != nil {
                    Button("Original-PDF anzeigen") { showPDF = true }.buttonStyle(.bordered)
                }
                Text(simple ? note.short : note.detailed).textSelection(.enabled)
                SourcesView(sources: note.sources)
            }.frame(maxWidth: .infinity, alignment: .leading).padding()
        }.navigationTitle(note.topic).navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showPDF) {
            if note.sources.indices.contains(simple ? 1 : 0) {
                NotePDFView(source: note.sources[simple ? 1 : 0], text: simple ? note.short : note.detailed)
            }
        }
    }
}
struct SourcesView: View {
    let sources: [String]
    var body: some View {
        if !sources.isEmpty {
            VStack(alignment: .leading, spacing: 5) {
                Text("Quellen").font(.headline)
                ForEach(Array(sources.enumerated()), id: \.offset) { _, source in Text(source).font(.footnote) }
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}
enum CardFilter: String, CaseIterable {
    case all = "Alle", unlearned = "Offen", learned = "Gelernt", favorite = "Merkliste"
}
struct CardsView: View {
    let catalog: Catalog
    @EnvironmentObject private var progress: ProgressStore
    @State private var search = ""
    @State private var filter = CardFilter.all
    var filtered: [Card] {
        catalog.cards.filter { card in
            let matches = search.isEmpty || card.topic.localizedCaseInsensitiveContains(search) || card.front.localizedCaseInsensitiveContains(search) || card.back.localizedCaseInsensitiveContains(search)
            switch filter {
            case .all: return matches
            case .unlearned: return matches && !progress.learned.contains(card.id)
            case .learned: return matches && progress.learned.contains(card.id)
            case .favorite: return matches && progress.favorites.contains(card.id)
            }
        }
    }
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Picker("Kartenfilter", selection: $filter) {
                        ForEach(CardFilter.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                    }
                    Text("\(filtered.count) Karten · \(catalog.cards.filter { progress.learned.contains($0.id) }.count) als gelernt markiert")
                        .font(.footnote).foregroundStyle(.secondary)
                    NavigationLink("Passende Karten lernen") { CardSessionView(cards: filtered, shuffle: false) }.disabled(filtered.isEmpty)
                    NavigationLink("Zufällig lernen") { CardSessionView(cards: filtered, shuffle: true) }.disabled(filtered.isEmpty)
                }
                Section {
                    ForEach(filtered) { card in
                        NavigationLink { CardDetailView(card: card) }
                        label: { VStack(alignment: .leading) {
                            Text(card.front)
                            HStack { Text(card.topic); if progress.favorites.contains(card.id) { Image(systemName: "star.fill").accessibilityLabel("Merkliste") }; if progress.learned.contains(card.id) { Image(systemName: "checkmark.circle.fill").accessibilityLabel("Gelernt") } }
                                .font(.caption).foregroundStyle(.secondary)
                        } }
                    }
                    if filtered.isEmpty { Text("Keine Karten für diesen Filter gefunden.").foregroundStyle(.secondary) }
                }
            }.navigationTitle("Karteikarten").searchable(text: $search, prompt: "Karte suchen")
        }
    }
}
struct CardDetailView: View {
    let card: Card
    @EnvironmentObject private var progress: ProgressStore
    var body: some View {
        ScrollView { VStack(alignment: .leading, spacing: 20) {
            Text(card.front).font(.headline)
            DisclosureGroup("Antwort anzeigen") { Text(card.back).frame(maxWidth: .infinity, alignment: .leading).padding(.top).textSelection(.enabled) }
            Button(progress.learned.contains(card.id) ? "Wieder als offen markieren" : "Als gelernt markieren") { progress.toggleLearned(card.id) }.buttonStyle(.bordered)
            Button(progress.favorites.contains(card.id) ? "Aus Merkliste entfernen" : "Zur Merkliste hinzufügen") { progress.toggleFavorite(card.id) }.buttonStyle(.bordered)
            SourcesView(sources: card.sources)
        }.frame(maxWidth: .infinity, alignment: .leading).padding() }
        .navigationTitle(card.topic).navigationBarTitleDisplayMode(.inline)
    }
}
struct CardSessionView: View {
    @State private var cards: [Card]
    @State private var index = 0
    @State private var answerVisible = false
    @EnvironmentObject private var progress: ProgressStore
    init(cards: [Card], shuffle: Bool) { _cards = State(initialValue: shuffle ? cards.shuffled() : cards) }
    var body: some View {
        ScrollView {
            if cards.indices.contains(index) {
                let card = cards[index]
                VStack(alignment: .leading, spacing: 20) {
                    Text("Karte \(index + 1) von \(cards.count) · \(card.topic)").font(.footnote).foregroundStyle(.secondary)
                    Text(card.front).font(.headline)
                    Button(answerVisible ? "Antwort ausblenden" : "Antwort anzeigen") { answerVisible.toggle() }.buttonStyle(.bordered)
                    if answerVisible { Text(card.back).textSelection(.enabled); SourcesView(sources: card.sources) }
                    Button(progress.learned.contains(card.id) ? "Wieder als offen markieren" : "Als gelernt markieren") { progress.toggleLearned(card.id) }.buttonStyle(.bordered)
                    Button(progress.favorites.contains(card.id) ? "Aus Merkliste entfernen" : "Zur Merkliste hinzufügen") { progress.toggleFavorite(card.id) }.buttonStyle(.bordered)
                    HStack {
                        Button("Zurück") { index -= 1; answerVisible = false }.disabled(index == 0)
                        Spacer()
                        Button("Weiter") { index += 1; answerVisible = false }.disabled(index + 1 >= cards.count)
                    }.buttonStyle(.bordered)
                }.frame(maxWidth: .infinity, alignment: .leading).padding()
            } else { Text("Keine Karten verfügbar.").padding() }
        }.navigationTitle("Karten lernen").navigationBarTitleDisplayMode(.inline)
    }
}
struct CasesView: View {
    let catalog: Catalog
    @EnvironmentObject private var progress: ProgressStore
    @State private var search = ""
    var body: some View {
        NavigationStack {
            List(catalog.cases.filter { item in
                search.isEmpty || (item.title ?? "").localizedCaseInsensitiveContains(search) || item.text.localizedCaseInsensitiveContains(search) ||
                (item.instructions ?? "").localizedCaseInsensitiveContains(search) || (item.answerNote ?? "").localizedCaseInsensitiveContains(search) ||
                item.diseases.contains { $0.localizedCaseInsensitiveContains(search) } ||
                item.questions.contains { $0.question.localizedCaseInsensitiveContains(search) || $0.answer.localizedCaseInsensitiveContains(search) }
            }) { item in
                NavigationLink { CaseView(item: item) }
                label: { VStack(alignment: .leading) {
                    Text(item.title?.isEmpty == false ? item.title! : "Fallbeispiel")
                    if let day = item.examDay { Text("Prüfungstag \(day)").font(.caption).foregroundStyle(.secondary) }
                    if progress.completedCases.contains(item.id) { Text("Abgeschlossen").font(.caption).foregroundStyle(.secondary) }
                } }
            }.navigationTitle("Fallbeispiele").searchable(text: $search, prompt: "Fall suchen")
        }
    }
}
struct CaseView: View {
    let item: StudyCase
    @EnvironmentObject private var progress: ProgressStore
    var body: some View {
        ScrollView { VStack(alignment: .leading, spacing: 18) {
            Button(progress.completedCases.contains(item.id) ? "Wieder als offen markieren" : "Als abgeschlossen markieren") { progress.toggleCase(item.id) }.buttonStyle(.bordered)
            Text(item.text).textSelection(.enabled)
            if let instructions = item.instructions, !instructions.isEmpty { Text(instructions).textSelection(.enabled) }
            ForEach(Array(item.questions.enumerated()), id: \.offset) { index, question in
                DisclosureGroup("\(index + 1). \(question.question)") {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(question.answer).textSelection(.enabled)
                        SourcesView(sources: question.sources)
                    }.frame(maxWidth: .infinity, alignment: .leading).padding(.top)
                }
            }
            if let answerNote = item.answerNote, !answerNote.isEmpty { Text(answerNote).textSelection(.enabled) }
            SourcesView(sources: item.sources)
        }.frame(maxWidth: .infinity, alignment: .leading).padding() }
        .navigationTitle(item.title?.isEmpty == false ? item.title! : "Fallbeispiel").navigationBarTitleDisplayMode(.inline)
    }
}
struct QuizView: View {
    let catalog: Catalog
    var days: [String] { Array(Set(catalog.quizzes.map(\.published))).sorted(by: >) }
    var body: some View {
        NavigationStack {
            List {
                Section("Veröffentlichte Quizfragen") {
                    if days.isEmpty { Text("Zurzeit sind keine Quizfragen im Offline-Katalog enthalten.").foregroundStyle(.secondary) }
                    ForEach(days, id: \.self) { day in
                        NavigationLink { QuizDayView(day: day, questions: catalog.quizzes.filter { $0.published == day }, catalog: catalog) }
                        label: { VStack(alignment: .leading) { Text(day); Text("\(catalog.quizzes.filter { $0.published == day }.count) Fragen").font(.caption).foregroundStyle(.secondary) } }
                    }
                }
                Section("Archiv") {
                    NavigationLink("Frühere Quizfragen öffnen") { QuizArchiveView(catalog: catalog) }
                }
            }.navigationTitle("Quiz")
        }
    }
}
struct QuizArchiveView: View {
    @StateObject private var store: QuizArchiveStore
    init(catalog: Catalog) { _store = StateObject(wrappedValue: QuizArchiveStore(catalog: catalog)) }
    var dates: [String] { Array(Set(store.cachedDates + store.summaries.map(\.date))).sorted(by: >) }
    var body: some View {
        List {
            Section {
                Text("Das Archiv lädt öffentliche Quizfragen. Der Inhaltsserver erhält dabei deine IP-Adresse; geöffnete Tage bleiben offline gespeichert.").font(.footnote).foregroundStyle(.secondary)
                Button(store.loadingPage ? "Lädt …" : "Archivliste laden") { store.loadPage() }.disabled(store.loadingPage)
                if !store.message.isEmpty { Text(store.message).font(.footnote).foregroundStyle(.secondary) }
            }
            Section("Quiztage") {
                ForEach(dates, id: \.self) { date in
                    NavigationLink { ArchivedDayView(store: store, date: date) }
                    label: { HStack { Text(date); Spacer(); if store.cachedDates.contains(date) { Text("Offline").font(.caption).foregroundStyle(.secondary) } } }
                }
                if store.hasLoaded && dates.isEmpty { Text("Keine Archivtage vorhanden.").foregroundStyle(.secondary) }
                if store.nextBefore != nil { Button("Ältere Tage laden") { store.loadPage(more: true) }.disabled(store.loadingPage) }
            }
        }.navigationTitle("Quizarchiv").navigationBarTitleDisplayMode(.inline)
    }
}
struct ArchivedDayView: View {
    @ObservedObject var store: QuizArchiveStore
    let date: String
    var body: some View {
        Group {
            if let day = store.day, day.date == date { QuizDayView(day: date, questions: day.quizzes, catalog: store.catalog) }
            else if store.loadingDay { ProgressView("Archivtag laden …") }
            else { Text(store.message.isEmpty ? "Archivtag nicht verfügbar." : store.message).padding() }
        }.task(id: date) { store.loadDay(date) }
    }
}
struct QuizDayView: View {
    let day: String
    let questions: [Quiz]
    let catalog: Catalog
    @EnvironmentObject private var progress: ProgressStore
    var body: some View {
        List {
            Section {
                Text("\(questions.filter { progress.answer(for: $0) != nil }.count) von \(questions.count) beantwortet")
                NavigationLink("Quiz als Übung starten oder fortsetzen") { QuizPracticeView(questions: questions.sorted { $0.id < $1.id }, catalog: catalog) }.disabled(!progress.quizAvailable || questions.isEmpty)
                Text("Dies ist eine lokale Übung, keine Ranglistenabgabe.").font(.footnote).foregroundStyle(.secondary)
                if !progress.quizAvailable { Text("Der gespeicherte Quizstand ist nicht lesbar. Neue Antworten werden nicht gespeichert.").foregroundStyle(.red) }
            }
            ForEach(questions) { quiz in
                NavigationLink { QuizQuestionView(quiz: quiz) }
                label: { VStack(alignment: .leading) {
                    Text(quiz.question)
                    Text(progress.answer(for: quiz) == nil ? "Offen" : "Beantwortet").font(.caption).foregroundStyle(.secondary)
                } }
            }
        }.navigationTitle(day).navigationBarTitleDisplayMode(.inline)
    }
}
// Local practice only; this never sends answers or scores to the ranking.
struct QuizPracticeView: View {
    let questions: [Quiz]
    let catalog: Catalog
    @EnvironmentObject private var progress: ProgressStore
    @State private var index: Int?
    @State private var confirmReset = false
    @State private var reviewWrong = false
    @State private var reviewing = false
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Lokale Übung · keine Ranglistenwertung").font(.footnote).foregroundStyle(.secondary)
                if !progress.quizAvailable {
                    Text("Der gespeicherte Quizstand ist nicht lesbar; die Übung ist gesperrt.").foregroundStyle(.red)
                } else if let position = index, questions.indices.contains(position) {
                    let q = questions[position]
                    Text("Frage \(position + 1) von \(questions.count) · \(q.topic)").font(.footnote)
                    Text(q.question).font(.headline)
                    ForEach(Array(q.options.enumerated()), id: \.offset) { option, text in
                        Button(text) { progress.select(option, for: q) }.buttonStyle(.bordered)
                            .disabled(progress.answer(for: q) != nil)
                    }
                    if let answer = progress.answer(for: q) {
                        Text(answer == q.correctIndex ? "Richtig" : "Nicht richtig").bold()
                        Text("Deine Antwort: \(q.options[answer])")
                        Text("Richtige Antwort: \(q.options[q.correctIndex])")
                        Text(q.explanation).textSelection(.enabled)
                        SourcesView(sources: q.sources)
                        Button("Nächste Frage") { index = nextUnanswered(after: position) }.buttonStyle(.borderedProminent)
                    }
                } else if let summary = progress.quizSummary(questions) {
                    Text("Übung beendet: \(summary.correct) von \(summary.total) richtig.").font(.headline)
                    Text("Das Ergebnis gilt nur für diese Übung und misst nicht dein gesamtes Wissen.").font(.footnote)
                    Text("Ergebnis nach Thema").font(.headline)
                    ForEach(summary.topics) { topic in
                        VStack(alignment: .leading, spacing: 4) {
                            Text("\(topic.topic): \(topic.correct) von \(topic.total) richtig")
                            if let note = catalog.notes.first(where: { $0.topic == topic.topic }) {
                                NavigationLink("Lernzettel öffnen") { NoteView(note: note) }
                            }
                        }
                    }
                    Button(reviewing && !reviewWrong ? "Alle Antworten ausblenden" : "Alle Antworten ansehen") {
                        if reviewing && !reviewWrong { reviewing = false } else { reviewing = true; reviewWrong = false }
                    }.buttonStyle(.bordered)
                    if summary.correct < summary.total {
                        Button(reviewing && reviewWrong ? "Falsche Antworten ausblenden" : "Falsche Antworten ansehen") {
                            if reviewing && reviewWrong { reviewing = false } else { reviewing = true; reviewWrong = true }
                        }.buttonStyle(.bordered)
                    }
                    if reviewing {
                        ForEach(questions.filter { !reviewWrong || progress.answer(for: $0) != $0.correctIndex }) { q in
                            NavigationLink(q.question) { QuizReviewView(quiz: q, note: catalog.notes.first { $0.topic == q.topic }) }
                        }
                    }
                } else if !questions.isEmpty {
                    Button("Übung fortsetzen") { index = questions.firstIndex { progress.answer(for: $0) == nil } }.buttonStyle(.borderedProminent)
                }
                if questions.contains(where: { progress.answer(for: $0) != nil }) {
                    Button("Quiz neu beginnen") { confirmReset = true }.buttonStyle(.bordered)
                }
            }.frame(maxWidth: .infinity, alignment: .leading).padding()
        }
        .navigationTitle("Quizübung").navigationBarTitleDisplayMode(.inline)
        .onAppear { if index == nil && progress.quizSummary(questions) == nil { index = questions.firstIndex { progress.answer(for: $0) == nil } } }
        .confirmationDialog("Quiz neu beginnen? Der lokale Übungsstand für diesen Tag wird gelöscht.", isPresented: $confirmReset, titleVisibility: .visible) {
            Button("Übungsstand zurücksetzen", role: .destructive) { progress.resetQuiz(questions); index = questions.isEmpty ? nil : 0; reviewing = false }
        }
    }
    private func nextUnanswered(after index: Int) -> Int? {
        if index + 1 < questions.count, let next = ((index + 1)..<questions.count).first(where: { progress.answer(for: questions[$0]) == nil }) { return next }
        return questions.indices.first { progress.answer(for: questions[$0]) == nil }
    }
}
struct QuizReviewView: View {
    let quiz: Quiz
    let note: Note?
    @EnvironmentObject private var progress: ProgressStore
    var body: some View {
        ScrollView { VStack(alignment: .leading, spacing: 16) {
            Text(quiz.question).font(.headline)
            if let answer = progress.answer(for: quiz) { Text("Deine Antwort: \(quiz.options[answer])") }
            Text("Richtige Antwort: \(quiz.options[quiz.correctIndex])").bold()
            Text(quiz.explanation).textSelection(.enabled)
            SourcesView(sources: quiz.sources)
            if let note = note { NavigationLink("Lernzettel öffnen") { NoteView(note: note) } }
        }.frame(maxWidth: .infinity, alignment: .leading).padding() }.navigationTitle(quiz.topic).navigationBarTitleDisplayMode(.inline)
    }
}
struct QuizQuestionView: View {
    let quiz: Quiz
    @EnvironmentObject private var progress: ProgressStore
    var body: some View {
        ScrollView { VStack(alignment: .leading, spacing: 16) {
            Text(quiz.question).font(.headline)
            ForEach(Array(quiz.options.enumerated()), id: \.offset) { index, option in
                Button(option) { progress.select(index, for: quiz) }.buttonStyle(.bordered).disabled(!progress.quizAvailable)
            }
            if let selection = progress.answer(for: quiz) {
                Text(selection == quiz.correctIndex ? "Richtig" : "Nicht richtig").bold()
                Text("Deine Antwort: \(quiz.options[selection])")
                Text("Richtige Antwort: \(quiz.options[quiz.correctIndex])")
                Text(quiz.explanation)
                SourcesView(sources: quiz.sources)
                Button("Antwort zurücksetzen") { progress.clearAnswer(for: quiz) }.buttonStyle(.bordered)
            }
        }.frame(maxWidth: .infinity, alignment: .leading).padding() }
        .navigationTitle(quiz.topic).navigationBarTitleDisplayMode(.inline)
    }
}
struct SettingsView: View {
    @ObservedObject var store: CatalogStore
    @EnvironmentObject private var progress: ProgressStore
    var body: some View {
        NavigationStack {
            Form {
                Section("Offline-Katalog") {
                    Text("Revision \(store.snapshot?.catalog.revision ?? 0)")
                    Button(store.loading ? "Aktualisierung läuft …" : "Inhalte aktualisieren") { store.refresh() }.disabled(store.loading)
                    if !store.message.isEmpty { Text(store.message).font(.footnote) }
                    Text("Die App prüft Inhalte beim Öffnen und ungefähr stündlich während der Nutzung. Der öffentliche Inhaltsserver erhält dabei deine IP-Adresse. Im Hintergrund sind Prüfungen nicht garantiert; bei Fehlern bleibt der Offline-Katalog erhalten.").font(.footnote).foregroundStyle(.secondary)
                }
                Section("Rangliste") {
                    NavigationLink("Öffentliche Rangliste anzeigen") { RankingView() }
                    Text("Nur Anzeige: Teilnahme und Wertung sind auf iOS noch nicht verfügbar.").font(.footnote).foregroundStyle(.secondary)
                }
                Section("Lokaler Lernstand") {
                    Text("Gelesene Themen: \(progress.readNotes.count)")
                    Text("Gelernt markierte Karten: \(progress.learned.count)")
                    Text("Merkliste: \(progress.favorites.count)")
                    Text("Abgeschlossene Fälle: \(progress.completedCases.count)")
                    Text("Markierungen bleiben auf diesem Gerät. Beim Löschen der App können sie verloren gehen; es gibt keine Synchronisierung mit Android.").font(.footnote).foregroundStyle(.secondary)
                }
                Section("Vorschau") {
                    Text("Original-PDFs müssen zum aktuellen Text passen; keine Ranglistenteilnahme oder vollständige Android-Funktionsgleichheit. Nicht als Ersatz für fachlich geprüfte Unterrichtsmaterialien verwenden.").font(.footnote)
                }
            }.navigationTitle("Mehr")
        }
    }
}

struct RankingView: View {
    @StateObject private var store = RankingStore()
    var body: some View {
        List {
            Section {
                Text("Öffentliche Spaßrangliste. Keine fälschungssichere Wertung; die Anzeige überträgt deine IP-Adresse an den Server.").font(.footnote)
                Button(store.loading ? "Lädt …" : "Rangliste laden") { store.load() }.disabled(store.loading)
                if store.error { Text("Rangliste nicht verfügbar. Bitte später erneut versuchen.").foregroundStyle(.secondary) }
            }
            if let snapshot = store.snapshot {
                Section("\(snapshot.totalParticipants) Teilnehmende") {
                    if snapshot.entries.isEmpty { Text("Noch keine gewerteten Einträge.") }
                    ForEach(snapshot.entries) { entry in
                        HStack {
                            Text("\(entry.rank).").monospacedDigit()
                            Text(entry.username)
                            Spacer()
                            Text("\(entry.points) Punkte").monospacedDigit()
                        }
                    }
                }
                Text("Stand: \(Date(timeIntervalSince1970: TimeInterval(snapshot.generatedAtMillis) / 1000).formatted())")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }.navigationTitle("Rangliste").navigationBarTitleDisplayMode(.inline)
    }
}
