import SwiftUI

struct RawLocationEditorView: View {
    @Bindable var model: YPtunApplicationModel
    let request: LocationEditorRequest

    @Environment(\.dismiss) private var dismiss
    @State private var text = "{}"
    @State private var isSaving = false

    var body: some View {
        NavigationStack {
            TextEditor(text: $text)
                .font(.system(.body, design: .monospaced))
                .textSelection(.enabled)
                .padding(8)
                .navigationTitle(request.locationID == nil ? "Новая локация" : "Редактировать")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Отмена") { dismiss() }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Сохранить") {
                            isSaving = true
                            Task {
                                let saved = await model.saveLocation(
                                    text: text,
                                    id: request.locationID
                                )
                                isSaving = false
                                if saved { dismiss() }
                            }
                        }
                        .disabled(isSaving)
                    }
                }
        }
        .task { text = await model.locationText(id: request.locationID) }
    }
}
