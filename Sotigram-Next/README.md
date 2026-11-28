# Sotigram Next — Android source

Clean Android/Jetpack Compose project for the Sotigram messenger.

## Build
Open the `Sotigram-Next` folder in Android Studio (Hedgehog or newer), use JDK 17, allow Gradle sync, then Build > Build APK(s). The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Backend
The app uses the existing Supabase project and only its publishable key. Never put a Supabase secret/service-role key in the Android app.

Registration maps a username to a synthetic email address (`username@sotigram.app`). Supabase Auth must be configured so that this address pattern is accepted and email confirmation is disabled or otherwise handled by a real email flow. If confirmation is enabled, signup will not immediately create an app session.

The current commit is a fresh UI/auth foundation. Chat rows and several navigation sections are intentionally presentation scaffolding; realtime messaging, media uploads, stories, Stars transactions, gifts, NFT purchases, admin actions, voice notes, and LiveKit call UI are not yet wired in this new source tree.

CI workflow refreshed.
