HOW TO ADD THE REAL CROP DISEASE MODEL
======================================

The app runs in DEMO MODE until you place a model here:

    app/src/main/assets/crop_disease_model.tflite

Requirements the app expects (see DiseaseClassifier.kt to change):
  - Input : 224 x 224 x 3, float32, normalised to [0, 1]
  - Output: float32[NUM_CLASSES] probabilities (softmax)
  - labels.txt must list the classes in the SAME ORDER as the output,
    one per line. The starter labels.txt matches the 38-class
    PlantVillage set.

WHERE TO GET A MODEL
--------------------
Option A - Convert a pretrained PlantVillage model:
  1. Train / download a Keras MobileNetV2 or EfficientNet-Lite model
     on the PlantVillage dataset (38 classes).
  2. Convert to TFLite:
        import tensorflow as tf
        conv = tf.lite.TFLiteConverter.from_keras_model(model)
        open("crop_disease_model.tflite","wb").write(conv.convert())
  3. Copy the .tflite into this assets/ folder.

Option B - Use a ready TFLite model from Hugging Face / TF Hub
  Search "plant disease tflite" and confirm the input/output shape
  above, then update labels.txt to match its classes.

IMPROVING FIELD ACCURACY
------------------------
PlantVillage images are close-up single leaves on plain backgrounds.
Drone footage is whole plants from 2-4 m in field light. For real use:
  - fly low and slow, keep leaves large in frame
  - collect a few hundred frames from YOUR drone over YOUR crop,
    label them, and fine-tune the model
  - optionally mix in the PlantDoc dataset (real-world field images)
