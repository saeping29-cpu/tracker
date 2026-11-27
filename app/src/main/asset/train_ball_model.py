from pathlib import Path
import tensorflow as tf

ROOT = Path("dataset")
OUT = Path("output")
OUT.mkdir(exist_ok=True)

ds = tf.keras.utils.image_dataset_from_directory(
    ROOT, labels="inferred", label_mode="binary", image_size=(224, 224),
    batch_size=8, validation_split=0.2, subset="both", seed=42
)
train, val = ds
model = tf.keras.Sequential([
    tf.keras.layers.Rescaling(1./255),
    tf.keras.layers.Conv2D(16, 3, activation="relu"),
    tf.keras.layers.MaxPooling2D(),
    tf.keras.layers.Conv2D(32, 3, activation="relu"),
    tf.keras.layers.GlobalAveragePooling2D(),
    tf.keras.layers.Dense(1, activation="sigmoid")
])
model.compile(optimizer="adam", loss="binary_crossentropy", metrics=["accuracy"])
model.fit(train, validation_data=val, epochs=15)
converter = tf.lite.TFLiteConverter.from_keras_model(model)
(OUT / "model.tflite").write_bytes(converter.convert())
print("created", OUT / "model.tflite")
