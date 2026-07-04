CROP DISEASE MODEL — ALREADY INCLUDED
=====================================

crop_disease_model.tflite IS bundled in this folder. The app does REAL
detection out of the box (no demo mode).

Model details:
  - Source : akshayrana30/plant-disease-detection (MobileNet, PlantVillage)
  - Input  : 200 x 200 x 3, float32, normalised to [0, 1]
  - Output : float32[39] softmax (38 PlantVillage classes + 1 background)
  - Labels : labels.txt, one per line, SAME ORDER as the model output
  - Size   : ~238 KB

Verified accuracy on the source dataset:
  - Diseased leaves: strong (apple scab 94%, corn rust 100%,
    grape black rot 100%, tomato early blight 73%)
  - Healthy apple / grape: 6/6 correct
  - Healthy tomato: only ~3/6 — expect false positives on healthy tomato

FIELD-CONDITION NOTE
--------------------
This was trained on close-up single leaves on plain backgrounds. Drone footage
is whole plants from 2-4 m in field light, so accuracy will drop. To improve:
fly low and slow, and later fine-tune on frames from YOUR drone over YOUR crop.

TO SWAP IN A DIFFERENT MODEL
----------------------------
Replace crop_disease_model.tflite and labels.txt, then in DiseaseClassifier.kt
update INPUT_SIZE if the new model's input differs from 200.
