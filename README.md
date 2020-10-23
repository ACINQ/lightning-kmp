An implementation of Lightning in kotlin-multiplatform, that runs on iOS and Android.

## Build & Tests

```
git clone https://github.com/ACINQ/eclair-kmp.git
cd eclair-kmp
./ gradlew allTests
```

## Contributing

### Code style

Use Intellij, with the following settings:
- File > Settings > Editor > Code Style
  - In the "Formatter Control" tab, check "Enable formatter markers in comments" 
- File > Settings > Editor > Code Style > Kotlin
  - select "Set from..." and choose "Kotline style guide"
  - set "Hard wrap at" to 240
