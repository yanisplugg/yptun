import SwiftUI
import UniformTypeIdentifiers

struct AddLocationView: View {
    @ObservedObject var model: YPtunApplicationModel
    @Environment(\.dismiss) private var dismiss
    @State private var importText = ""
    @State private var isImporting = false
    @State private var isFileImporterPresented = false
    @State private var isScannerPresented = false

    var body: some View {
        NavigationStack {
            List {
                Section("Ссылка или конфигурация") {
                    TextEditor(text: $importText)
                        .frame(minHeight: 92)
                        .font(.body.monospaced())
                        .accessibilityLabel("Ссылка или конфигурация")
                    PasteButton(payloadType: String.self) { values in
                        importText = values.joined(separator: "\n")
                    }
                    Button {
                        performImport(importText)
                    } label: {
                        if isImporting {
                            ProgressView()
                                .frame(maxWidth: .infinity)
                        } else {
                            Label("Импортировать", systemImage: "square.and.arrow.down")
                                .frame(maxWidth: .infinity)
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(importText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isImporting)
                }
                Section("Быстро") {
                    #if os(iOS)
                    Button {
                        isScannerPresented = true
                    } label: {
                        Label("Сканировать QR-код", systemImage: "qrcode.viewfinder")
                    }
                    #endif
                    Button {
                        isFileImporterPresented = true
                    } label: {
                        Label("Импортировать файл", systemImage: "doc.badge.plus")
                    }
                }
                Section("Настроить") {
                    Button {
                        importText = "https://"
                    } label: {
                        Label("Добавить подписку", systemImage: "dot.radiowaves.left.and.right")
                    }
                    Button {
                        dismiss()
                        model.locationEditorRequest = LocationEditorRequest(locationID: nil)
                    } label: {
                        Label("Создать вручную", systemImage: "slider.horizontal.3")
                    }
                    option("Найти бесплатные серверы", symbol: "sparkle.magnifyingglass")
                }
            }
            .navigationTitle("Добавить")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Закрыть") { dismiss() }
                }
            }
        }
        .fileImporter(
            isPresented: $isFileImporterPresented,
            allowedContentTypes: [.json, .plainText, .data]
        ) { result in
            do {
                let url = try result.get()
                let accessing = url.startAccessingSecurityScopedResource()
                defer { if accessing { url.stopAccessingSecurityScopedResource() } }
                let data = try Data(contentsOf: url)
                guard let text = String(data: data, encoding: .utf8) else {
                    throw CocoaError(.fileReadInapplicableStringEncoding)
                }
                importText = text
                performImport(text)
            } catch {
                model.presentedNotice = YPtunNotice(
                    title: "Не удалось прочитать файл",
                    message: error.localizedDescription
                )
            }
        }
        #if os(iOS)
        .fullScreenCover(isPresented: $isScannerPresented) {
            NavigationStack {
                QRCodeScannerView { value in
                    isScannerPresented = false
                    importText = value
                    performImport(value)
                }
                .ignoresSafeArea()
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Закрыть") { isScannerPresented = false }
                    }
                }
            }
        }
        #endif
    }

    private func option(_ title: String, symbol: String) -> some View {
        Button {
            dismiss()
            model.showPendingFeature(title)
        } label: {
            Label(title, systemImage: symbol)
        }
    }

    private func performImport(_ text: String) {
        isImporting = true
        Task {
            let imported = await model.importLocations(from: text)
            isImporting = false
            if imported { dismiss() }
        }
    }
}
