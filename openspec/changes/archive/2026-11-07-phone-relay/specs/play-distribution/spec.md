## MODIFIED Requirements

### Requirement: Talk to no one but the user's desktop

The app SHALL open network connections only to the addresses of the desktop it is paired with or of the pairing link the user
is pairing with, and — only while the paired desktop has given the phone a relay grant — to the team server that grant names
(`desktop-connection`, "Reach the desktop through the team server"). It SHALL include no analytics, advertising or
crash-reporting library, and send nothing to its developer or any third party; what it sends through the team server is
end-to-end encrypted to the desktop. Camera frames SHALL be decoded on the phone and neither stored nor sent. A link in shown
text SHALL open in another app only when the user taps it. The privacy policy the app links to SHALL state each of these,
and what the team server can see when the relay is used.

#### Scenario: The app's own sockets while in use

- **WHEN** the app is paired and used through every screen on the emulator, and the sockets owned by the app's UID are listed
  every second meanwhile
- **THEN** every remote address among them is one of the paired desktop's addresses, or the team server named in the
  desktop's relay grant while the phone holds one

#### Scenario: No other host in the code

- **WHEN** `./gradlew check` runs
- **THEN** it fails if a Kotlin string literal or a string resource under `app/src/main` holds a URL other than the privacy policy's (in each language) and the source
  repository's (and its issue page), or if the release runtime classpath holds a library of a listed analytics, advertising or crash-reporting
  vendor (Firebase, Google Mobile Ads and Analytics, Crashlytics, Sentry, Bugsnag, AppsFlyer, Adjust, Mixpanel, Amplitude)
