import SwiftUI

@main
struct PflegeExamenApp: App {
    var body: some Scene { WindowGroup { RootView() } }
}

struct RootView: View {
    @StateObject private var store = CatalogStore(bundleURL: Bundle.main.url(forResource: "learning_content", withExtension: "json"))
    @StateObject private var progress = ProgressStore()
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
                    Text("iOS-Vorschau: Original-PDFs und Rangliste sind noch nicht verfügbar.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Section("Themen") {
                    ForEach(catalog.notes.filter { search.isEmpty || $0.topic.localizedCaseInsensitiveContains(search) }) { note in
                        NavigationLink {
                            NoteView(note: note)
                        } label: {
                            HStack { Text(note.topic); Spacer(); if progress.readNotes.contains(note.topic) { Image(systemName: "checkmark.circle.fill").accessibilityLabel("Gelesen") } }
                        }
                    }
                }
            }.navigationTitle("Lernzettel").searchable(text: $search, prompt: "Thema suchen")
        }
    }
}
struct NoteView: View {
    let note: Note
    @EnvironmentObject private var progress: ProgressStore
    @State private var simple = false
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Picker("Version", selection: $simple) {
                    Text("Neu").tag(false); Text("Einfach").tag(true)
                }.pickerStyle(.segmented)
                Button(progress.readNotes.contains(note.topic) ? "Als ungelesen markieren" : "Als gelesen markieren") {
                    progress.toggleRead(note.topic)
                }.buttonStyle(.bordered)
                Text(simple ? note.short : note.detailed).textSelection(.enabled)
                SourcesView(sources: note.sources)
            }.frame(maxWidth: .infinity, alignment: .leading).padding()
        }.navigationTitle(note.topic).navigationBarTitleDisplayMode(.inline)
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
            let matches = search.isEmpty || card.topic.localizedCaseInsensitiveContains(search) || card.front.localizedCaseInsensitiveContains(search)
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
struct CasesView: View {
    let catalog: Catalog
    @State private var search = ""
    var body: some View {
        NavigationStack {
            List(catalog.cases.filter { search.isEmpty || ($0.title ?? "").localizedCaseInsensitiveContains(search) || $0.text.localizedCaseInsensitiveContains(search) }) { item in
                NavigationLink { CaseView(item: item) }
                label: { VStack(alignment: .leading) {
                    Text(item.title?.isEmpty == false ? item.title! : "Fallbeispiel")
                    if let day = item.examDay { Text("Prüfungstag \(day)").font(.caption).foregroundStyle(.secondary) }
                } }
            }.navigationTitle("Fallbeispiele").searchable(text: $search, prompt: "Fall suchen")
        }
    }
}
struct CaseView: View {
    let item: StudyCase
    var body: some View {
        ScrollView { VStack(alignment: .leading, spacing: 18) {
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
    var body: some View {
        NavigationStack {
            if catalog.quizzes.isEmpty { Text("Zurzeit sind keine Quizfragen im Offline-Katalog enthalten.").padding().navigationTitle("Quiz") }
            else {
                List(catalog.quizzes) { quiz in
                    NavigationLink { QuizQuestionView(quiz: quiz) }
                    label: { VStack(alignment: .leading) { Text(quiz.question); Text(quiz.published).font(.caption).foregroundStyle(.secondary) } }
                }.navigationTitle("Quiz")
            }
        }
    }
}
struct QuizQuestionView: View {
    let quiz: Quiz
    @State private var selection: Int?
    var body: some View {
        ScrollView { VStack(alignment: .leading, spacing: 16) {
            Text(quiz.question).font(.headline)
            ForEach(Array(quiz.options.enumerated()), id: \.offset) { index, option in
                Button(option) { selection = index }.buttonStyle(.bordered)
            }
            if let selection = selection {
                Text(selection == quiz.correctIndex ? "Richtig" : "Nicht richtig").bold()
                Text("Richtige Antwort: \(quiz.options[quiz.correctIndex])")
                Text(quiz.explanation)
                SourcesView(sources: quiz.sources)
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
                    Text("Nur nach Antippen: Der öffentliche Inhaltsserver erhält deine IP-Adresse. Bei Fehlern bleibt der bisherige Katalog erhalten.").font(.footnote).foregroundStyle(.secondary)
                }
                Section("Lokaler Lernstand") {
                    Text("Gelesene Themen: \(progress.readNotes.count)")
                    Text("Gelernt markierte Karten: \(progress.learned.count)")
                    Text("Merkliste: \(progress.favorites.count)")
                    Text("Markierungen bleiben auf diesem Gerät. Beim Löschen der App können sie verloren gehen; es gibt keine Synchronisierung mit Android.").font(.footnote).foregroundStyle(.secondary)
                }
                Section("Vorschau") {
                    Text("Keine Original-PDFs, Rangliste oder vollständige Android-Funktionsgleichheit. Nicht als Ersatz für fachlich geprüfte Unterrichtsmaterialien verwenden.").font(.footnote)
                }
            }.navigationTitle("Mehr")
        }
    }
}
