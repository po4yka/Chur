# Local Content Search

Search can find filenames, captions, tags, and indexed words from documents or images in the open vault. It does not search the public Notes shell. Private notes keep their separate search screen.

## Use

1. Open a vault and select **Search**.
2. Select **Index new items and retry failures**. This also processes supported files that were imported before content search was added.
3. Search for a word or several words. Results open the existing document or media viewer. A query can combine a filename word with a word from the contents.
4. Use **Index options** to rebuild all supported items or clear recognized text. Clearing the content index preserves originals, filenames, captions, and tags.

New imports become scan candidates. Recognition runs only after a foreground scan action. It stops when the app enters the background or the vault locks. Completed writes remain searchable after stop, lock, and restart. Retry processes failures again. Rebuild replaces successful records only after new extraction succeeds.

## Supported input and limits

| Input | Local extraction |
| --- | --- |
| Photo or image file | Image OCR |
| PDF, including scanned pages | Page rasterization and OCR |
| `text/*`, JSON, XML | Strict UTF-8 text; no markup execution |
| Office documents, archives, audio, video, other binary files | No content extraction; metadata search remains available |

Image and PDF sources are limited to 32 MiB. PDF scans process at most 100 pages, one bounded raster at a time. Text extraction reads at most a 1 MiB prefix. Each item's index stores at most 256 KiB of UTF-8. The summary counts partial results and failures that need retry. A partial record is searched normally and can be replaced with **Rebuild all supported items**. Password-protected, malformed, unavailable, or over-limit inputs can fail without changing the original.

OCR uses English and Russian models on Android and the available local English and Russian Vision recognizers on iOS. Recognition quality depends on the image, font, layout, and platform. OCR can miss or misread words. Search matches literal word prefixes; it does not provide semantic similarity or substring search within a word.

## Storage and transfer

Recognized text and FTS pages stay inside the same SQLCipher database as the private catalog. Source ranges and page pixels are processed in memory. OCR does not send content to a server or write it to public files, notifications, system search, or saved UI state.

Trashed objects are excluded from Search and can reuse their index when restored. Permanent deletion removes their recognized text before the deletion checkpoint. Explicit index clear removes content search terms; metadata terms remain. Neither action removes earlier backups or establishes physical flash erasure.

A full encrypted backup includes the index. Selective encrypted export, synchronization, and sharing transfer originals without this local derived index. Run indexing on the receiving device after import or restore as needed.
