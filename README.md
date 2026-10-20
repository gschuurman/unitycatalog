# unitycatalog fork: CI

This branch only holds the automation for the fork. The code lives on
[`s3-compatible-endpoint`](../../tree/s3-compatible-endpoint): upstream
[unitycatalog/unitycatalog](https://github.com/unitycatalog/unitycatalog) `main` plus a short stack
of fork commits that add credential vending for S3-compatible stores such as RustFS (see
`docs/server/s3-compatible.md` there).

[`rebase-uc.yml`](.github/workflows/rebase-uc.yml) runs daily:

1. Replays the fork commits onto the latest upstream `main`.
2. Checks that no fork line was dropped (`.github/scripts/verify_rebase.py`).
3. Runs the credential tests and the RustFS end-to-end check.
4. Force-pushes `s3-compatible-endpoint` and publishes `ghcr.io/gschuurman/unitycatalog`
   (`latest`, `<version>-s3.<date>`, `upstream-<sha>`).

A conflict, dropped content or failing test leaves the branch and image as they were and opens an
issue with the manual rebase steps. Run it by hand from the Actions tab; tick `force-image` to
rebuild the image when upstream hasn't moved.
