//
//  InOutView.swift
//  Eklair
//
//  Copyright © 2020 Acinq. All rights reserved.
//

import Foundation
import SwiftUI

struct InOutView: View {

    var body: some View {

        NavigationView {
            VStack(alignment: .center) {
                Text("InOut view")

                Spacer()
            }
            .padding(.top, 40)
            .navigationBarTitle("In Out", displayMode: .inline)
        }
    }
}

// MARK: - Previews

struct InOutView_Previews: PreviewProvider {
    static var previews: some View {
        Group {
            InOutView()
                .previewDevice("iPhone SE")
        }
    }
}
