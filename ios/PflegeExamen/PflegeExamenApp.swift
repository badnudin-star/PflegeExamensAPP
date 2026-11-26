import SwiftUI

@main
struct PflegeExamenApp: App {
    var body: some Scene {
        WindowGroup { RootView() }
    }
}

struct RootView: View {
    @State private var catalog: Catalog?
    @State private var error = false
    var body: some View {
        Group {
            if let catalog = catalog {
                TabView {
                    NotesView(catalog: catalog).tabItem { Label("Lernzettel", systemImage: "doc.text") }
                    CardsView(catalog: catalog).tabItem { Label("Karten", systemImage: "rectangle.stack") }
                    CasesView(catalog: catalog).tabItem { Label("Fälle", systemImage: "person.text.rectangle") }
                    QuizView(catalog: catalog).tabItem { Label("Quiz", systemImage: "checkmark.circle") }
                }
            } else if error {
                Text("Der Offline-Katalog konnte nicht geladen werden. Bitte eine geprüfte Version verwenden.")
                    .padding()
            } else { ProgressView() }
        }.task {
            guard catalog == nil, !error else { return }
            do {
                guard let url = Bundle.main.url(forResource: "learning_content", withExtension: "json") else { throw CatalogError.invalid }
                catalog = try Catalog.load(from: url)
            } catch { error = true }
        }
    }
}

struct NotesView: View {
    let catalog: Catalog
    @State private var search = ""
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("Technische Vorschau: noch keine Original-PDFs, Inhaltsaktualisierung oder Rangliste.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Section("Themen") {
                    ForEach(catalog.notes.filter { search.isEmpty || $0.topic.localizedCaseInsensitiveContains(search) }) { note in
                        NavigationLink(note.topic) { NoteView(note: note) }
                    }
                }
            }.navigationTitle("Lernzettel").searchable(text: $search, prompt: "Thema suchen")
        }
    }
}
struct NoteView: View {
    let note: Note
    @State private var simple = false
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Picker("Version", selection: $simple) {
                    Text("Neu").tag(false); Text("Einfach").tag(true)
                }.pickerStyle(.segmented)
                Text(simple ? note.short : note.detailed).textSelection(.enabled)
                if !note.sources.isEmpty {
                    Text("Quellen").font(.headline)
                    ForEach(note.sources, id: \.self) { Text($0).font(.footnote) }
                }
            }.frame(maxWidth: .infinity, alignment: .leading).padding()
        }.navigationTitle(note.topic).navigationBarTitleDisplayMode(.inline)
    }
}
struct CardsView: View {
    let catalog: Catalog
    @State private var search = ""
    var body: some View {
        NavigationStack {
            List(catalog.cards.filter { search.isEmpty || $0.topic.localizedCaseInsensitiveContains(search) || $0.front.localizedCaseInsensitiveContains(search) }) { card in
                NavigationLink { ScrollView { VStack(alignment: .leading, spacing: 20) {
                    Text(card.front).font(.headline)
                    DisclosureGroup("Antwort anzeigen") { Text(card.back).frame(maxWidth: .infinity, alignment: .leading).padding(.top) }
                }.frame(maxWidth: .infinity, alignment: .leading).padding() } }
                label: { VStack(alignment: .leading) { Text(card.front); Text(card.topic).font(.caption).foregroundStyle(.secondary) } }
            }.navigationTitle("Karteikarten").searchable(text: $search, prompt: "Karte suchen")
        }
    }
}
struct CasesView: View {
    let catalog: Catalog
    var body: some View {
        NavigationStack {
            List(catalog.cases) { item in
                NavigationLink { CaseView(item: item) }
                label: { VStack(alignment: .leading) {
                    Text(item.title.isEmpty ? "Fallbeispiel" : item.title)
                    if let day = item.examDay { Text("Prüfungstag \(day)").font(.caption).foregroundStyle(.secondary) }
                } }
            }.navigationTitle("Fallbeispiele")
        }
    }
}
struct CaseView: View {
    let item: StudyCase
    var body: some View {
        ScrollView { VStack(alignment: .leading, spacing: 18) {
            Text(item.text).textSelection(.enabled)
            ForEach(Array(item.questions.enumerated()), id: \.offset) { index, question in
                DisclosureGroup("\(index + 1). \(question.question)") {
                    Text(question.answer).frame(maxWidth: .infinity, alignment: .leading).padding(.top)
                }
            }
        }.frame(maxWidth: .infinity, alignment: .leading).padding() }
        .navigationTitle(item.title.isEmpty ? "Fallbeispiel" : item.title).navigationBarTitleDisplayMode(.inline)
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
            }
        }.frame(maxWidth: .infinity, alignment: .leading).padding() }
        .navigationTitle(quiz.topic).navigationBarTitleDisplayMode(.inline)
    }
}
