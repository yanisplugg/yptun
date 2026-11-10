import SwiftUI

struct AddLocationView: View {
    @Bindable var model: YPtunApplicationModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("Быстро") {
                    option("Вставить из буфера", symbol: "doc.on.clipboard")
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
