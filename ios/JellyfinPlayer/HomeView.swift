import Foundation
import SwiftUI

struct HomeView: View {
    @AppStorage("serverAddress") private var serverAddress = ""

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if serverAddress.isEmpty {
                        Label("尚未设置服务器", systemImage: "server.rack")
                            .foregroundStyle(.secondary)
                    } else {
                        LabeledContent("服务器", value: serverAddress)
                    }
                    NavigationLink("连接设置") {
                        ConnectionView(serverAddress: $serverAddress)
                    }
                }
            }
            .navigationTitle("媒体库")
        }
    }
}

private struct ConnectionView: View {
    @Binding var serverAddress: String
    @Environment(\.dismiss) private var dismiss
    @State private var address: String

    init(serverAddress: Binding<String>) {
        _serverAddress = serverAddress
        _address = State(initialValue: serverAddress.wrappedValue)
    }

    private var normalizedAddress: String? {
        let value = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let parts = URLComponents(string: value),
              let scheme = parts.scheme?.lowercased(), ["http", "https"].contains(scheme),
              let host = parts.host, !host.isEmpty,
              parts.user == nil, parts.password == nil,
              parts.query == nil, parts.fragment == nil,
              parts.url != nil else { return nil }
        return value
    }

    var body: some View {
        Form {
            TextField("Jellyfin 服务器地址", text: $address)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
            Button("保存服务器地址") {
                guard let normalizedAddress else { return }
                serverAddress = normalizedAddress
                dismiss()
            }
            .disabled(normalizedAddress == nil)
        }
        .navigationTitle("连接设置")
        .navigationBarTitleDisplayMode(.inline)
    }
}
