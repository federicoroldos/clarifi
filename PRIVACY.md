# ClariFi Privacy Policy

_Last updated: 1 August 2026_

ClariFi is a personal finance tracker. It has no accounts, no sign-in, no analytics
and no advertising. The developer operates no server and receives none of your data.

## What ClariFi stores, and where

Everything you enter — accounts, balances, transactions and recurring payments —
is stored **on your device**, in the app's own private storage. It is not uploaded
anywhere unless you explicitly ask for it, using one of the two features below.

Uninstalling the app deletes that data.

## What leaves your device, and only when you ask

**Scanning a receipt or importing a bank statement.** These features are optional
and do nothing until you supply your own API key for an AI provider. When you scan
a receipt, the photo is sent to the provider you chose — [Groq](https://groq.com/privacy-policy/),
[Google Gemini](https://policies.google.com/privacy) or
[Anthropic Claude](https://www.anthropic.com/legal/privacy) — which reads it and
returns the amount, date, merchant and category. Importing a statement sends the
pages of the PDF the same way. The request goes from your device straight to that
provider under your own key; it does not pass through the developer. What the
provider does with it is governed by their privacy policy, linked above.

**Cloud sync.** Optional. If you paste the connection string of a
[Supabase](https://supabase.com/privacy) Postgres database, Push and Pull copy the
whole database between your device and **your own** database. The developer has no
access to it. Nothing syncs automatically: data moves only when you press Push or
Pull.

## Credentials

Your AI API key and your database connection string are held in Android's
encrypted preferences, backed by the device keystore. They are never included in a
JSON export, never uploaded to the cloud database, and never sent anywhere except
to the service they authenticate.

## Permissions

- **Camera** — only to photograph a receipt, and only while that screen is open.
  Photos are used for the scan and are not kept by the app.
- **Internet** — used by the three optional features above (AI scanning, cloud
  sync, and checking GitHub for a new version) and by nothing else.
- **Notifications** — local reminders that a recurring payment is due. They are
  generated on the device; nothing is sent to deliver them.

## Children

ClariFi is not directed at children and collects nothing about anyone.

## Changes

Any change to this policy will be published in this file, whose history is public
in the repository.

## Contact

Open an issue at <https://github.com/federicoroldos/clarifi/issues>.
