import SwiftUI

struct AddLocationView: View {
    @Bindable var model: YPtunApplicationModel
    @Environment(\.dismiss) private var dismiss
    @State private var importText = ""
    @State private var isImporting = false

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
                        isImporting = true
                        Task {
                            let imported = await model.importLocations(from: importText)
                            isImporting = false
                            if imported { dismiss() }
                        }
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
                    option("Сканировать QR-код", symbol: "qrcode.viewfinder")
                    option("Импортировать файл", symbol: "doc.badge.plus")
                }
                Section("Настроить") {
                    option("Добавить подписку", symbol: "dot.radiowaves.left.and.right")
                    option("Создать вручную", symbol: "slider.horizontal.3")
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
    }

    private func option(_ title: String, symbol: String) -> some View {
        Button {
            dismiss()
            model.showPendingFeature(title)
        } label: {
            Label(title, systemImage: symbol)
        }
    }
}
