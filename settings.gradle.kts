rootProject.name="eklair"

include(":common")

val include_android: String by extra
if (include_android == "true") {
    println("Include Android")
    include(":phoenix-android")
}
