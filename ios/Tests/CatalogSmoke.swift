import Foundation
import PDFKit

@main
struct CatalogSmoke {
    static func main() async throws {
        if CommandLine.arguments.count == 3 && CommandLine.arguments[1] == "--worker-test" {
            guard let port = Int(CommandLine.arguments[2]) else { fatalError("Ungültiger Testport") }
            try await RankingWorkerSmoke.run(port: port)
            return
        }
        try await RankingAccountSmoke.run()
        try await RankedParticipationSmoke.run()
        let url = URL(fileURLWithPath: CommandLine.arguments[1])
        let raw = try Data(contentsOf: url)
        let catalog = try Catalog.decode(raw)
        precondition(!catalog.notes.isEmpty)
        print("Katalog Revision \(catalog.revision): \(catalog.notes.count) Themen, \(catalog.cards.count) Karten, \(catalog.cases.count) Fälle, \(catalog.quizzes.count) Quizfragen")
        let searchNote = Note(topic: "Herz", short: "einfache Wörter", balanced: "Lang", detailed: "Langer Text", sources: ["Quelle"])
        precondition(noteSearchMatch(searchNote, query: " HERZ ").title)
        precondition(noteSearchMatch(searchNote, query: "EINFACHE").simple && !noteSearchMatch(searchNote, query: "EINFACHE").detailed)
        precondition(noteSearchMatch(searchNote, query: "langer").detailed)
        precondition(!noteSearchMatch(searchNote, query: "nicht vorhanden").found)
        let searchCase = StudyCase(id: "case-search", published: "2026-01-01", title: "Fall", text: "Text", examDay: 1, diseases: ["Erkrankung"], instructions: "Hinweise", answerNote: "Anmerkung", sources: ["Quelle"], questions: [CaseQuestion(question: "Aufgabe", answer: "Versteckte Antwort", sources: ["Quelle"])])
        precondition(caseSearchMatch(searchCase, query: " versteckte ") && caseSearchMatch(searchCase, query: "ERKRANKUNG"))
        precondition(!caseSearchMatch(searchCase, query: "nicht vorhanden"))
        print("Suche über beide Lernzettelversionen und Fallantworten geprüft")
        let invalid = Catalog(schemaVersion: 99, revision: 0, notes: catalog.notes, cards: [], cases: [], quizzes: [])
        do { try invalid.validate(); fatalError("Ungültiges Schema akzeptiert") }
        catch CatalogError.invalid { print("Ungültiges Schema abgelehnt") }

        let temporary = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: temporary) }
        try FileManager.default.createDirectory(at: temporary, withIntermediateDirectories: true)
        let stored = temporary.appendingPathComponent("catalog.json")
        let store = CatalogStore(bundleURL: url, fileURL: stored)
        precondition(store.snapshot?.catalog.revision == catalog.revision)
        let unchanged = try store.applyDownloaded(raw)
        precondition(!unchanged)
        var object = try JSONSerialization.jsonObject(with: raw) as! [String: Any]
        object["revision"] = catalog.revision + 1
        var cards = object["cards"] as! [[String: Any]]
        var cases = object["cases"] as! [[String: Any]]
        precondition(!cards.isEmpty && !cases.isEmpty)
        cards[0]["back"] = "Aktualisierte Kartenantwort"
        cases[0]["text"] = "Aktualisierte Fallsituation"
        object["cards"] = cards
        object["cases"] = cases
        let newer = try JSONSerialization.data(withJSONObject: object)
        let updated = try store.applyDownloaded(newer)
        precondition(updated)
        precondition(store.snapshot?.catalog.revision == catalog.revision + 1)
        precondition(store.snapshot?.catalog.cards[0].back == "Aktualisierte Kartenantwort")
        precondition(store.snapshot?.catalog.cases[0].text == "Aktualisierte Fallsituation")
        let reopened = CatalogStore(bundleURL: url, fileURL: stored)
        precondition(reopened.snapshot?.catalog.revision == catalog.revision + 1)
        precondition(reopened.snapshot?.catalog.cards[0].back == "Aktualisierte Kartenantwort")
        precondition(reopened.snapshot?.catalog.cases[0].text == "Aktualisierte Fallsituation")
        func rejected(_ data: Data) {
            do { try store.applyDownloaded(data); fatalError("Ungültiges Update akzeptiert") } catch {}
            precondition(store.snapshot?.catalog.revision == catalog.revision + 1)
            precondition((try? Data(contentsOf: stored)) == newer)
        }
        rejected(raw) // rollback
        object["revision"] = catalog.revision + 1
        object["unexpectedChange"] = true
        rejected(try JSONSerialization.data(withJSONObject: object)) // same revision conflict
        object.removeValue(forKey: "unexpectedChange")
        object["revision"] = catalog.revision + 2
        object["cards"] = []
        rejected(try JSONSerialization.data(withJSONObject: object)) // missing cards
        object["cards"] = cards
        object["cases"] = []
        rejected(try JSONSerialization.data(withJSONObject: object)) // missing cases
        object["cases"] = cases
        object["notes"] = []
        rejected(try JSONSerialization.data(withJSONObject: object)) // missing notes
        rejected(Data("{}".utf8))
        print("Katalogupdate: Persistenz, Rollback, Konflikt und Fehlerfall geprüft")

        // A declared retirement survives skipped revisions, but silent removal,
        // tombstone loss and resurrection of old IDs must remain impossible.
        let original = try CatalogSnapshot(raw)
        var migration = try JSONSerialization.jsonObject(with: raw) as! [String: Any]
        var migratedCards = migration["cards"] as! [[String: Any]]
        let oldID = migratedCards[0]["id"] as! String
        migratedCards[0]["id"] = "card-v2-synthetic"
        migration["cards"] = migratedCards
        migration["revision"] = catalog.revision + 1
        migration["retiredCardIds"] = [oldID]
        let migrated = try original.replacing(with: JSONSerialization.data(withJSONObject: migration))
        migration["revision"] = catalog.revision + 2
        migration["retiredCardIds"] = []
        do { _ = try migrated.replacing(with: JSONSerialization.data(withJSONObject: migration)); fatalError("Tombstone verloren") } catch {}
        migration["retiredCardIds"] = [oldID, "card-v2-synthetic"]
        do { _ = try migrated.replacing(with: JSONSerialization.data(withJSONObject: migration)); fatalError("Aktive ID ist stillgelegt") } catch {}
        migration["retiredCardIds"] = [oldID]
        _ = try migrated.replacing(with: JSONSerialization.data(withJSONObject: migration))
        print("Explizite und dauerhafte Kartenstilllegung geprüft")

        let suite = "pflege.ios.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let progress = ProgressStore(defaults: defaults)
        progress.toggleLearned("card-1"); progress.toggleFavorite("card-2"); progress.toggleRead("topic-1")
        let loaded = ProgressStore(defaults: defaults)
        precondition(loaded.learned == ["card-1"] && loaded.favorites == ["card-2"] && loaded.readNotes == ["topic-1"])
        loaded.toggleLearned("card-1"); loaded.toggleFavorite("card-2"); loaded.toggleRead("topic-1")
        let cleared = ProgressStore(defaults: defaults)
        precondition(cleared.learned.isEmpty && cleared.favorites.isEmpty && cleared.readNotes.isEmpty)
        print("Lernmarkierungen: Speichern, Wiederherstellen und Zurücksetzen geprüft")
        cleared.toggleCase("case-1")
        let quiz = Quiz(id: "q1", published: "2026-01-01", topic: "Thema", question: "Frage?", options: ["a", "b", "c", "d"], correctIndex: 0, explanation: "Grund", sources: ["Quelle"])
        cleared.select(2, for: quiz)
        let quizLoaded = ProgressStore(defaults: defaults)
        precondition(quizLoaded.completedCases == ["case-1"] && quizLoaded.answer(for: quiz) == 2)
        let changedQuiz = Quiz(id: "q1", published: "2026-01-01", topic: "Thema", question: "Neue Frage?", options: ["a", "b", "c", "d"], correctIndex: 0, explanation: "Grund", sources: ["Quelle"])
        precondition(quizLoaded.answer(for: changedQuiz) == nil)
        quizLoaded.clearAnswer(for: quiz)
        precondition(ProgressStore(defaults: defaults).answer(for: quiz) == nil)
        let quiz2 = Quiz(id: "q2", published: "2026-01-01", topic: "Thema", question: "Andere Frage?", options: ["a", "b", "c", "d"], correctIndex: 1, explanation: "Grund", sources: ["Quelle"])
        let otherDay = Quiz(id: "q3", published: "2026-01-02", topic: "Weiteres Thema", question: "Frage?", options: ["a", "b", "c", "d"], correctIndex: 0, explanation: "Grund", sources: ["Quelle"])
        quizLoaded.select(0, for: quiz)
        quizLoaded.select(0, for: otherDay)
        precondition(quizLoaded.quizSummary([quiz, quiz2]) == nil)
        quizLoaded.select(0, for: quiz2)
        let summary = quizLoaded.quizSummary([quiz, quiz2, otherDay])!
        precondition(summary.correct == 2 && summary.total == 3 && summary.topics.count == 2)
        precondition(summary.topics[0].correct == 1 && summary.topics[0].total == 2)
        let reopenedQuiz = ProgressStore(defaults: defaults)
        precondition(reopenedQuiz.quizSummary([quiz, quiz2])?.correct == 1)
        reopenedQuiz.resetQuiz([quiz, quiz2])
        let resetQuiz = ProgressStore(defaults: defaults)
        precondition(resetQuiz.answer(for: quiz) == nil && resetQuiz.answer(for: quiz2) == nil && resetQuiz.answer(for: otherDay) == 0)
        precondition(resetQuiz.quizSummary([]) == nil)
        print("Quizübung: Fortsetzen, Ergebnis nach Thema und tageweises Zurücksetzen geprüft")
        defaults.set("damaged", forKey: "ios.preview.quizAnswers.v1")
        let damaged = ProgressStore(defaults: defaults)
        precondition(!damaged.quizAvailable)
        damaged.select(1, for: quiz)
        precondition(defaults.string(forKey: "ios.preview.quizAnswers.v1") == "damaged")
        print("Fall- und Quizstand: Persistenz, Inhaltswechsel und beschädigte Daten geprüft")

        var schedule = AutoRefreshSchedule()
        let now = Date(timeIntervalSince1970: 1_000_000)
        precondition(schedule.shouldRefresh(now: now))
        schedule.started(now: now)
        precondition(!schedule.shouldRefresh(now: now.addingTimeInterval(1799)))
        schedule.completed(now: now, success: false)
        precondition(schedule.shouldRefresh(now: now.addingTimeInterval(1800)))
        schedule.completed(now: now, success: true)
        precondition(!schedule.shouldRefresh(now: now.addingTimeInterval(3599)))
        precondition(schedule.shouldRefresh(now: now.addingTimeInterval(3600)))
        print("Aktualisierungstakt geprüft")

        let board = Data(#"{"generatedAtMillis":1000,"nextUpdateAtMillis":3601000,"totalParticipants":1,"entries":[{"rank":1,"username":"Test","points":3,"quizzes":1}]}"#.utf8)
        let ranking = try RankingSnapshot.decode(board)
        precondition(ranking.entries.count == 1 && ranking.entries[0].points == 3)
        let invalidBoard = Data(#"{"generatedAtMillis":1000,"nextUpdateAtMillis":3601000,"totalParticipants":1,"entries":[{"rank":2,"username":"Test","points":51,"quizzes":1}]}"#.utf8)
        do { _ = try RankingSnapshot.decode(invalidBoard); fatalError("Ungültige Rangliste akzeptiert") } catch {}
        print("Öffentliche Rangliste: gültige und ungültige Daten geprüft")

        precondition(archiveQuizDates(catalog: ["2026-11-13", "2026-11-12", "2026-11-15"],
            cached: ["2026-11-12", "2026-11-11"], online: ["2026-11-10", "invalid"], today: "2026-11-13") == ["2026-11-12", "2026-11-11", "2026-11-10"])
        let beforeBerlinMidnight = ISO8601DateFormatter().date(from: "2026-11-12T22:59:59Z")!
        let afterBerlinMidnight = ISO8601DateFormatter().date(from: "2026-11-12T23:00:00Z")!
        precondition(berlinQuizDate(now: beforeBerlinMidnight) == "2026-11-12")
        precondition(berlinQuizDate(now: afterBerlinMidnight) == "2026-11-13")
        precondition(validArchiveDate("2024-02-29") && !validArchiveDate("2023-02-29") && !validArchiveDate("../../bad"))
        precondition(PublicEndpoint.archiveDay("../../bad").url == nil)
        let pageData = Data(#"{"days":[{"date":"2026-01-02","questions":1},{"date":"2026-01-01","questions":2}],"nextBefore":"2026-01-01"}"#.utf8)
        let page = try ArchivePage.decode(pageData, before: nil)
        precondition(page.days.count == 2 && page.nextBefore == "2026-01-01")
        do { _ = try ArchivePage.decode(pageData, before: "2026-01-02"); fatalError("Ungültige Seitengrenze akzeptiert") } catch {}
        var archiveQuiz = try JSONSerialization.jsonObject(with: JSONEncoder().encode(quiz)) as! [String: Any]
        archiveQuiz["topic"] = catalog.notes[0].topic
        let archiveData = try JSONSerialization.data(withJSONObject: ["date": "2026-01-01", "quizzes": [archiveQuiz]])
        let archiveCache = ArchiveCache(directory: temporary.appendingPathComponent("archive"))
        let savedDay = try archiveCache.writeNew(archiveData, date: "2026-01-01", catalog: catalog)
        precondition(savedDay.quizzes.count == 1 && archiveCache.dates() == ["2026-01-01"])
        let reloadedDay = try archiveCache.read("2026-01-01", catalog: catalog)
        precondition(reloadedDay?.quizzes.count == 1)
        do { _ = try archiveCache.writeNew(archiveData, date: "2026-01-01", catalog: catalog); fatalError("Archivtag überschrieben") } catch {}
        let archivedBytes = try Data(contentsOf: archiveCache.path("2026-01-01"))
        precondition(archivedBytes == archiveData)
        do { _ = try ArchiveDay.decode(archiveData, expected: "2026-01-02", catalog: catalog); fatalError("Ungültiges Datum akzeptiert") } catch {}
        print("Quizarchiv: Kalender, Seiten, Offline-Cache und Schreibschutz geprüft")
        let pdfDirectory = URL(fileURLWithPath: CommandLine.arguments[2])
        let pdf = NotePDFLocator.locate(directory: pdfDirectory, source: "Lernzettel: Synthetisch.pdf", text: "Synthetischer Lernzettel.")
        precondition(pdf != nil)
        precondition(PDFDocument(url: pdf!)?.pageCount == 2)
        precondition(NotePDFLocator.locate(directory: pdfDirectory, source: "Lernzettel: Synthetisch.pdf", text: "Geänderter Text") == nil)
        precondition(NotePDFLocator.locate(directory: pdfDirectory, source: "Lernzettel: ../Synthetisch.pdf", text: "Synthetischer Lernzettel.") == nil)
        print("PDF-Zuordnung: übereinstimmenden Text, veralteten Text und Pfadschutz geprüft")
    }
}
