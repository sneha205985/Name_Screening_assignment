## Name Screening Assignment

### Overview

This project implements a **Mini Name Screening Service** that compares a person’s full name (and optional aliases) against a JSON watchlist to identify potential matches.  
It simulates a realistic **AML / compliance name screening workflow** using string normalization and fuzzy similarity scoring. No database is used – everything is file and JSON based.

The service:

- **Accepts** a full name and multiple aliases from an input JSON file.
- **Normalizes** all names for consistent comparison.
- **Calculates** similarity scores using a custom **token‑aware, Levenshtein‑based** algorithm.
- **Identifies** the overall **best match** across all aliases.
- **Ranks** the **top 3 closest watchlist matches**.
- **Produces** structured JSON outputs (detailed and consolidated).

---

### Features

- **Full name + aliases**
  - Input can contain a primary `fullName` and a list of `aliases`.
  - The service chooses the **best overall match** across all of them.

- **Name normalization**
  - Lowercasing and trimming of whitespace.
  - Unicode accent stripping (e.g., `José` → `jose`).
  - Removal of punctuation and collapsing of multiple spaces.

- **Fuzzy similarity scoring**
  - Custom algorithm in `Similarity.score(...)` that:
    - Uses **Levenshtein distance** to compute a character‑level similarity ratio.
    - Uses **token overlap** (including support for initials like `A N Iyer`).
    - Handles **token reordering** via sorted and set‑based token forms.
    - Adds small bonuses for aligned first/last name tokens.
  - Outputs a final score in the range \([0.0, 1.0]\), where higher is a closer textual match.

- **Match classification**
  - `score >= 0.90` → `EXACT_MATCH`
  - `0.75 <= score <= 0.89` → `POSSIBLE_MATCH`
  - `score < 0.75` → `NO_MATCH`

- **Traceability**
  - Each scored match records **which input name or alias** produced the score.
  - `detailed.json` keeps both **raw** and **normalized** forms for transparency.

- **JSON‑based I/O**
  - Inputs, watchlist, and outputs are all plain JSON files, easy to inspect and test.

- **Defensive behavior**
  - Skips reprocessing when output files already exist.
  - Logs all major steps with the `requestId`.
  - Validates presence and validity of input and watchlist files.

---

### Tech Stack

- **Language**: Java 17  
- **Build**: Maven  
- **HTTP server**: Java built‑in `HttpServer` (`com.sun.net.httpserver.HttpServer`)  
- **JSON**: Jackson (`jackson-databind`)

---

### Project Structure

```text
name-screening-assignment/
├── src/main/java/com/example/screening
│   ├── Main.java
│   ├── ScreeningService.java
│   ├── Similarity.java
│   ├── NameUtils.java
│   └── JsonModels.java
├── data/
│   └── user1/req1/
│       ├── input/
│       │   └── input.json
│       └── output/
│           ├── consolidated.json
│           └── detailed.json
├── watchlist.json
├── pom.xml
└── README.md
```

Key components:

- `Main.java` – bootstraps the HTTP server.
- `ScreeningService.java` – HTTP routing, file I/O, main screening flow, and logging.
- `Similarity.java` – token‑aware similarity scoring.
- `NameUtils.java` – name normalization and token sorting utilities.
- `JsonModels.java` – input and output data models.

---

### Prerequisites

Ensure the following are installed:

- **Java 17**

```bash
java -version
```

- **Maven**

```bash
mvn -version
```

Maven will automatically download all required dependencies.

---

### How to Run the Project

1. **Clone the repository**

```bash
git clone <repository-url>
cd name-screening-assignment
```

2. **Build and run the application**

```bash
mvn clean compile exec:java -Dexec.mainClass="com.example.screening.Main"
```

3. **Server endpoint**

- The HTTP server starts on:

  `http://127.0.0.1:5050`

- Screening is triggered via:

  `POST /process/{userId}/{requestId}`

  Example:

```bash
curl -X POST http://127.0.0.1:5050/process/user1/req1
```

On success, the server responds with a small JSON payload such as:

```json
{
  "ok": true,
  "status": "processed",
  "outputPath": "data/user1/req1/output"
}
```

If output files already exist, `status` will be `"skipped"`.

---

### Input and Watchlist Files

#### Input file

The input is provided as a JSON file:

`data/{userId}/{requestId}/input/input.json`

Example (`data/user1/req1/input/input.json`):

```json
{
  "requestId": "req1",
  "fullName": "Arvind Narain Iyer",
  "aliases": [
    "A N Iyer",
    "Arvind N Iyer",
    "Arvind Iyer"
  ],
  "country": "IN"
}
```

Fields:

- `requestId` – ID for the screening request (optional; falls back to `{requestId}` path segment).
- `fullName` – primary name to be screened.
- `aliases` – optional list of alternative spellings / orderings / initials.
- `country` – optional country code for contextual information (passed through to output).

#### Watchlist file

The watchlist is a JSON array at the project root:

`watchlist.json`

Example:

```json
[
  { "id": "WL-7841", "name": "Arvind Narayan Iyer" },
  { "id": "WL-7842", "name": "Meera Krishnamurthy" }
  // ...
]
```

---

### Output

Once processing completes, output is written to:

`data/{userId}/{requestId}/output/`

Two files are generated:

1. **`consolidated.json`** – high‑level screening result
   - `requestId`
   - `matchType` (`EXACT_MATCH`, `POSSIBLE_MATCH`, `NO_MATCH`)
   - `bestMatchId` (null when `NO_MATCH`)
   - `score` (best match score)
   - `timestamp` (ISO‑8601)

2. **`detailed.json`** – full detail for analysis and debugging
   - `requestId`
   - `country`
   - `inputNames` – list of `{ raw, normalized }` for all input names and aliases.
   - `bestMatch`
     - `watchlistId`
     - `watchlistName`
     - `score`
     - `matchType`
     - `matchedUsingInput` – `{ raw, normalized }` of the input that produced the best score.
   - `top3Matches`
     - Up to 3 **unique** watchlist entries with:
       - `watchlistId`
       - `watchlistName`
       - `score`
       - `matchedUsingInputRaw`
       - `inputNormalized`

This separation allows quick consumption via `consolidated.json` while still offering deep traceability in `detailed.json`.

---

### How Matching Works (High‑Level)

- **Normalization**
  - All input and watchlist names are normalized using `NameUtils.normalize`:
    - Lowercase text.
    - Strip accents.
    - Remove punctuation.
    - Collapse multiple spaces.

- **Candidate names**
  - Collects `fullName` and all non‑blank `aliases` as candidate input names.
  - If no valid names are present, processing fails with a clear error.

- **Pairwise comparison**
  - Every normalized input name is compared with every normalized watchlist name.
  - For each pair, `Similarity.score`:
    - Builds token lists for both sides.
    - Computes a token overlap score (including partial support for initials).
    - Builds token‑sorted and token‑set representations to handle reordering.
    - Computes multiple Levenshtein‑based similarity ratios on different forms.
    - Blends these components plus small bonuses for matching first/last tokens.

- **Best match & top 3**
  - The single highest‑scoring pair across all input names and aliases becomes the **best match**.
  - All scored pairs are sorted to derive the **top 3 unique watchlist entries**.
  - The classification (`EXACT_MATCH`, `POSSIBLE_MATCH`, `NO_MATCH`) is determined from the best score.

- **Important note**
  - The engine is **purely text‑based**; it does **not** understand semantics or verify real‑world identity.
  - This mirrors real screening engines, which surface candidates based on textual similarity for human review.

---

### Error Handling & Logging

- If the **input** file or **watchlist** file is missing or invalid:
  - A descriptive error is logged with the `requestId`.
  - The HTTP endpoint returns a 400 response with a JSON error object.
  - No output files are created.

- If an unexpected internal error occurs:
  - The error is logged.
  - The HTTP endpoint returns a 500 response.

- If `detailed.json` and `consolidated.json` already exist for a request:
  - The service logs that reprocessing is skipped.
  - The endpoint returns `{ "ok": true, "status": "skipped", ... }`.

Logging format (example):

```text
[requestId=req1] Start processing userId=user1
[requestId=req1] Loaded input names=4, watchlist=20
[requestId=req1] Best match id=WL-7841 score=0.9063 type=EXACT_MATCH
[requestId=req1] Wrote output to data/user1/req1/output
```

---

### Notes

- The `target/` directory is generated automatically by Maven and **should not be committed** to version control.
- Similarity scores are in the range \([0.0, 1.0]\).
- Higher scores indicate closer textual similarity; they are **not** proof of identity or KYC.

---

### Author

**Sneha Gupta**

