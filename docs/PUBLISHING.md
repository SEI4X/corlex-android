# Release and ownership

Repository: https://github.com/SEI4X/corlex-android. Sources enter an owner-reviewed PR descended from this public repository's bootstrap main, never from private Git parents. Contributors do not self-merge. CI uses GitHub-hosted runners, pinned actions, read-only permissions, no secrets or pull_request_target execution.

Before a draft release: inspect the exact file manifest; pass secret, dependency/provenance/license and security reviews; run standalone builds/tests and native consumer checks; record source revision and SHA-256 asset checksums; verify license/NOTICE/SBOM inventories; review the changelog. Keep a release candidate/draft until owner acceptance. No npm/pub.dev/Maven identity or publication is assumed.

SDK versions follow semantic versioning after the first approved release. Fixes must preserve wire compatibility or describe migration. Security reports use this repository's private advisory channel.
