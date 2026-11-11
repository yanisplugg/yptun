import SwiftUI
import YPtunMigrationSupport

struct RawSettingsEditorView: View {
    let title: String
    let file: AppleSettingsFile
    let bootstrapper: AppleApplicationBootstrapper

    @State private var text = "{}"
    @State private var message: String?

    var body: some View {
        TextEditor(text: $text)
            .font(.system(.body, design: .monospaced))
            .textSelection(.enabled)
            .padding(8)
            .navigationTitle(title)
            .toolbar {
                Button("Сохранить") {
                    Task {
                        do {
                            try await bootstrapper.saveSettingsText(text, file: file)
                            message = "Сохранено"
                        } catch {
                            message = error.localizedDescription
                        }
                    }
                }
            }
            .task {
                do {
                    text = try await bootstrapper.settingsText(file)
                } catch {
                    message = error.localizedDescription
                }
            }
            .alert("Настройки", isPresented: Binding(
                get: { message != nil },
                set: { if !$0 { message = nil } }
            )) {
                Button("OK", role: .cancel) { message = nil }
            } message: {
                Text(message ?? "")
            }
    }
}
