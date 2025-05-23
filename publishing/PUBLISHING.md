# Publishing lightning-kmp artifacts

Releases are published to the Sonatype staging repository. If all items are valid they will be published to `maven central` repository.

- Download `release.zip` generated by the `Publish release` github action (which is triggered every time you publish a github release)
- unzip `release.zip` in the `publishing` directory
- sign all artifacts with a valid gpg key: `find release -type f -print -exec gpg -ab {} \;`
- run `lightning-kmp-staging-upload.sh`
- log into sonatype, close and publish your staging repository. Artifacts will be available on Maven Central within a few hours.

For example:

```shell
$ VERSION=1.2.3 OSS_USER=my_sonatype_username ./lightning-kmp-staging-upload.sh
```