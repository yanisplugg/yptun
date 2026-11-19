import SwiftUI
import YPtunShared

struct TunnelLogView: View {
    @State private var text = ""
    @State private var searchFilter = ""
    @State private var errorMessage: String?
    @State private var isCopied = false

    var body: some View {
        VStack(spacing: 0) {
            // Search / Filter Bar
            HStack(spacing: 8) {
                Image(systemName: "line.3.horizontal.decrease.circle")
                    .font(.system(size: 15))
                    .foregroundStyle(YPtunColor.textMuted)
                TextField("Фильтр логов (sing-box, error, dns…)", text: $searchFilter)
                    .font(.system(size: 13, design: .monospaced))
                if !searchFilter.isEmpty {
                    Button {
                        searchFilter = ""
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 13))
                            .foregroundStyle(YPtunColor.textMuted)
                    }
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(YPtunColor.cardSurface)
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 6)

            // Terminal View
            ScrollViewReader { proxy in
                ScrollView {
                    Text(filteredText.isEmpty ? (text.isEmpty ? "Лог пока пуст." : "Нет совпадений с фильтром.") : filteredText)
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundStyle(filteredText.isEmpty ? YPtunColor.textMuted : YPtunColor.textPrimary)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(14)
                        .id("bottom")
                }
                .background(YPtunColor.canvas)
                .clipShape(RoundedRectangle(cornerRadius: 16))
                .padding(.horizontal, 16)
                .padding(.bottom, 8)
            }
        }
        .background(YPtunColor.canvas.ignoresSafeArea())
        .navigationTitle("Журнал логов")
        .toolbar {
            ToolbarItemGroup(placement: .primaryAction) {
                Button {
                    YPtunPasteboard.setString(text)
                    isCopied = true
                } label: {
                    Label("Копировать", systemImage: "doc.on.doc")
                }

                Button {
                    load()
                } label: {
                    Label("Обновить", systemImage: "arrow.clockwise")
                }

                Menu {
                    Button(role: .destructive) {
                        do {
                            try AppleTunnelStore.writeText("", to: AppleTunnelStore.logFileName)
                            text = ""
                        } catch {
                            errorMessage = error.localizedDescription
                        }
                    } label: {
                        Label("Очистить журнал", systemImage: "trash")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .task { load() }
        .preferredColorScheme(.dark)
        .alert("Скопировано", isPresented: $isCopied) {
            Button("OK", role: .cancel) { isCopied = false }
        } message: {
            Text("Весь журнал логов скопирован в буфер обмена.")
        }
        .alert("Не удалось прочитать лог", isPresented: Binding(
            get: { errorMessage != nil },
            set: { if !$0 { errorMessage = nil } }
        )) {
            Button("OK", role: .cancel) { errorMessage = nil }
        } message: {
            Text(errorMessage ?? "")
        }
    }

    private var filteredText: String {
        guard !searchFilter.isEmpty else { return text }
        let q = searchFilter.lowercased()
        let lines = text.components(separatedBy: .newlines)
        return lines.filter { $0.lowercased().contains(q) }.joined(separator: "\n")
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
