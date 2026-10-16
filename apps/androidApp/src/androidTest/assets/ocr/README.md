# Local OCR test fixture

`protected.pdf` is the project-owned one-page AES-256 PDF fixture from `DocumentsAndroidTest`. Its public test password is `document-test-password`. OCR must reject the file because it receives no password. No user data is present.

English and Russian image and PDF fixtures are made in memory by `ContentOcrAndroidTest` with Android Canvas and PdfDocument.
