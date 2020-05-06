//
//  ContentView.swift
//  Playground
//
//  Copyright © 2020 Acinq. All rights reserved.
//

import SwiftUI

struct ContentView: View {
    @State var nodeManager: NodeManager = NodeManager()

    var body: some View {
        TabView {
            HashView(nodeManager: nodeManager)
                .tabItem {
                    VStack {
                        Image(systemName: "tag.circle")
                        Text("Hash")
                    }
            }.tag(1)

            InOutView()
                .tabItem {
                    VStack {
                        Image(systemName: "bolt.circle")
                        Text("InOut")
                    }
            }.tag(2)

            SocketView(nodeManager: nodeManager)
                .tabItem {
                    VStack {
                        Image(systemName: "link.circle")
                        Text("Socket")
                    }
            }.tag(3)
        }
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView(nodeManager: NodeManager())
    }
}
