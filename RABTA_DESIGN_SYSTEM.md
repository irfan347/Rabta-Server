# Rabta 2.0 design system

The app continues to use Kotlin, programmatic Android Views, `ThemedActivity`, platform dialogs and existing AndroidX core. No new UI dependency, backend or database change is required. Mesh, accounts, message protocols, calls and Status retain their existing implementations.

## Tokens and components

- Graphite canvas `#0B0F19`, dark surface `#151B29`, raised surface `#1D2636`.
- Light canvas `#F6F7FB`, white surface, restrained `#EEF0F7` raised surface.
- Brand violet `#8868F7` stays in the logo. Interactive fills use `#6D46D9` for AA white labels; dark-mode foreground accents use `#B5A0FF`.
- Teal highlights: `#43D7C5` in dark mode, `#096F65` in light mode for readable small text. Meaningful status also uses text, rather than colour alone.
- Native sans-serif with medium headings/actions; sp sizes and the existing app text-size setting multiply the system accessibility font scale.
- 4/8dp spacing rhythm, 20dp screen gutters, 16dp control corners, 20dp cards, 24dp dialogs and 28dp sheet corners. Controls are at least 48dp; primary buttons are at least 52dp tall.
- `AppTheme`: saved System/Light/Dark palettes, including foreground, secondary, error and accent colours.
- `RabtaUi`: text, card, button, icon button, ripple, loading, empty/error states, bottom sheet, system bars and inset handling.
- `FeatureUi` reuses these components so Settings, Status, Starred and Nearby stay consistent.
- Native dialog themes style existing alerts and choice dialogs. Bottom sheets reuse real actions and scroll on short displays. Transitions are 140ms alpha animations and respect Android's disabled-animation setting.

## Navigation

The existing conversation Activity hosts Chats, Nearby, Spaces and Calls. Chats retain all existing conversations and search. Spaces filters the existing local group conversations and opens the existing group chat/create-group flows; it does not imply a new channel/community backend. Nearby shares `NearbyPanel` with the standalone `NearbyActivity`: QR trust verification, permission tools, connection status, connected phones and pending message information remain real. Calls use existing local call history and conversation entry points.

Status remains accessible through its 48dp header action and the More menu. Profile, Appearance, Settings and Starred messages remain accessible. The selected destination and separate chat/group queries survive activity recreation; Back returns to Chats before leaving home.

## System integration and verification

`ThemedActivity` draws edge to edge with AndroidX WindowCompat and applies system-bar, display-cutout and IME insets to the original root padding. Navigation hides while searching with the keyboard; the chat composer stays above the IME. System-bar appearance uses InsetsController without overwriting layout flags, including on call screens.

`DesignSystemTest` verifies real group filtering and all four navigation destinations, Status access, touch targets, AA colour contrast, system font scaling, keyboard/composer placement and captures light/dark UI. Existing interface and connection tests remain enabled. CI exercises API 29 and 34 and collects screenshots even when another device test fails.

The audit in RABTA_ROADMAP.md records baseline failures separately. A passing build does not establish two-phone or physical mesh delivery.

## Home screen

The Chats home keeps the Rabta brand header, current account avatar, live server and nearby connection text, and a rounded search field. Search and the All, Unread and Groups filters operate on existing SQLite conversation/message data; unread counts come from saved conversation rows and groups use the existing `is_group` field. Rows show the saved peer name, last-message preview, local timestamp and unread count, with remote profile photos where available. An empty conversation shows a neutral no-messages label.

New chat opens a native rounded bottom sheet and resolves the entered username or Rabta ID through the existing account lookup. Calls continue to use the existing local call history and call flows. Status remains reachable from the header and More menu. The Chats, Nearby, Spaces and Calls navigation destinations remain intact.

## Conversation screen

Conversation rows use responsive incoming/outgoing bubbles, local-format timestamps, date separators, real receipt labels and explicit retry/resume actions. Failed-message retry calls the existing Outbox with the same message object and ID. Reply previews, copy, local delete, reactions, forwarding and starred messages retain their existing stored formats/actions. The reply composer has an explicit cancel control; rejected sends preserve the draft. Rich replies still require internet because the existing mesh transport supports plain text and bundled stickers.

The header uses the saved contact name and existing profile-photo cache, and labels connection information as this phone's connection rather than claiming peer presence. The connection banner and attachment sheet describe actual transport support. Native system/IME insets and bounded bubbles handle the keyboard and narrow screens; history refresh keeps the scroll position when reading earlier messages and follows accepted sends.

Voice messages retain their existing payload and use `VoiceMessageView` for actual decoded duration, pause/resume, seek and progress. No decorative waveform or byte-based duration estimate is shown. Audio is loaded on demand and playback/temp files are released when the screen pauses or the view is removed. Conversation device tests use isolated SQLite fixtures and localhost WebSocket acknowledgements to verify reply/copy/delete, retry ID preservation and decoded audio duration.
