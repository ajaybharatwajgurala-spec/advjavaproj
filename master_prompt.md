build a java facial attendance system. goal: face recognition attendance must actually work end to end. nothing else matters. minimal tokens: no explanations, no tests, no readme, no refactors, no extra features. write each file once, correctly.

stack (fixed, do not change)
- java 17, maven, single module
- ui: java swing only
- webcam: com.github.sarxos:webcam-capture
- face detection + embeddings: djl (ai.djl) with onnxruntime or pytorch engine
- storage: sqlite via org.xerial:sqlite-jdbc (file: attendance.db, auto-created on first run)
- no python, no external services, no spring

features (only these 3)
1. register: enter name + roll/id, camera opens, capture 5 face samples, compute embedding per sample, store average embedding as blob in sqlite
2. attendance: live camera feed, detect face every ~300ms, compute embedding, cosine similarity against all stored embeddings, if score >= 0.6 (make it a constant THRESHOLD) mark present
3. view: simple jtable of today's attendance (name, id, time) + button to export csv

rules
- one attendance row per person per day (unique constraint on person_id + date), ignore repeat detections
- run recognition on a background thread, never block the swing edt
- release the webcam cleanly on window close
- draw a box + name label on the live feed
- show "unknown" for faces below threshold, do not mark them
- tables: persons(id, name, roll_no, embedding blob), attendance(id, person_id, date, time)

models
- use djl model zoo / djl-provided face detection + face feature models. download at first run, cache locally.
- if djl model loading fails for any reason, fall back automatically to javacv (opencv haar cascade detection + lbph recognizer) so the app still works. log which engine is active at startup.

structure (keep it small)
- Main.java, CameraPanel.java, FaceEngine.java, Db.java, RegisterDialog.java, AttendancePanel.java, pom.xml

output requirements
- code must compile and run with `mvn clean package` then `java -jar target/app.jar` (shaded jar, main class set)
- full pom.xml with all dependencies and versions pinned
- after writing, run the build once, fix compile errors only, then stop
- do not add anything not listed above