# Drone Crop Vision

An Android app that overlays crop-disease detection boxes on top of your drone's
video app, using screen capture. Works with **any** drone (E99 or otherwise) —
it reads pixels off the screen, so it never talks to the drone directly.

## What it does
- Captures the screen while your drone's stock video app is open
- Splits each frame into a 3×3 grid, classifies each tile with a TensorFlow Lite
  crop-disease model, and draws a red box on tiles that look diseased
- **Save** button stores the annotated frame + findings
- **History** screen to review and delete saved captures

## Runs out of the box in DEMO MODE
Without a model it draws one clearly-labelled demo box so you can verify the
capture → overlay → save pipeline. Drop a real model into
`app/src/main/assets/crop_disease_model.tflite` to enable real detection.
See `app/src/main/assets/MODEL_README.txt`.

## Build & install
See the chat instructions, or in short:
1. Install Android Studio.
2. Open this folder (File → Open).
3. Let Gradle sync (downloads dependencies, needs internet).
4. Connect your phone (USB debugging on) and press ▶ Run,
   or Build → Build APK to get an installable file.

## Permissions the app asks for (and why)
- **Draw over other apps** — to show boxes on top of the drone app
- **Screen capture** — to read the video frames
- **Notifications** — required for the foreground service
- **Ignore battery optimisation** — stops Xiaomi/Realme/Oppo/Vivo from killing
  capture mid-flight

## Honest limits
- Detection quality depends entirely on the model you add. PlantVillage models
  are trained on close-up single leaves, so fly low and slow for best results.
- Screen capture adds a little latency; this is best for steady, low passes.
