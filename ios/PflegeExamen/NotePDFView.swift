import SwiftUI
import PDFKit

struct NotePDFView: View {
    let source: String
    let text: String
    @Environment(\.dismiss) private var dismiss
    var document: PDFDocument? {
        guard let directory = Bundle.main.url(forResource: "note_pdfs", withExtension: nil),
              let url = NotePDFLocator.locate(directory: directory, source: source, text: text),
              let pdf = PDFDocument(url: url), (1...100).contains(pdf.pageCount) else { return nil }
        return pdf
    }
    var body: some View {
        NavigationStack {
            Group {
                if let pdf = document { PDFDocumentView(document: pdf) }
                else { Text("Das passende Original-PDF ist in dieser App-Version nicht verfügbar. Der Text bleibt weiterhin lesbar.").padding() }
            }
            .navigationTitle("Original-Lernzettel").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Schließen") { dismiss() } } }
        }
    }
}
struct PDFDocumentView: UIViewRepresentable {
    let document: PDFDocument
    func makeUIView(context: Context) -> PDFView {
        let view = PDFView()
        view.autoScales = true
        view.displayMode = .singlePageContinuous
        view.displayDirection = .vertical
        view.document = document
        return view
    }
    func updateUIView(_ view: PDFView, context: Context) {
        if view.document !== document { view.document = document }
    }
}
