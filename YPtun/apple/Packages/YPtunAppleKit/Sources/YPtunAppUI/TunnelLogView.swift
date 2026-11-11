import SwiftUI
import YPtunShared

struct TunnelLogView: View {
    @State private var text = ""
    @State private var errorMessage: String?

    var body: some View {
        ScrollView {
            Text(text.isEmpty ? "Лог пока пуст." : text)
                .font(.system(.caption, design: .monospaced))
                .foregroundStyle(text.isEmpty ? .secondary : .primary)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()
        }
        .navigationTitle("Логи")
        .toolbar {
            Button {
                load()
            } label: {
                Label("Обновить", systemImage: "arrow.clockwise")
            }
            Button(role: .destructive) {
                do {
                    try AppleTunnelStore.writeText("", to: AppleTunnelStore.logFileName)
                    text = ""
                } catch {
                    errorMessage = error.localizedDescription
                }
            } label: {
                Label("Очистить", systemImage: "trash")
            }
        }
        .task { load() }
        .alert("Не удалось прочитать лог", isPresented: Binding(
            get: { errorMessage != nil },
            set: { if !$0 { errorMessage = nil } }
        )) {
            Button("OK", role: .cancel) { errorMessage = nil }
        } message: {
            Text(errorMessage ?? "")
        }
    }

    private func load() {
        do {
            text = try AppleTunnelStore.readText(AppleTunnelStore.logFileName) ?? ""
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
