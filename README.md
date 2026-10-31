# Ambiance Recorder

Grabadora de sonido ambiente para Android. Graba en WAV de 48 kHz y 24 bit, ordena las grabaciones por sesiones y las sirve por la red local para bajarlas al ordenador sin cables.

Kotlin, sin dependencias aparte de AndroidX Core.

## Qué hay

- **Grabación**: estéreo si el micrófono lo permite y, si no, mono. Usa la entrada sin procesar (sin control de ganancia, supresión de ruido ni ecualización) si el móvil la admite. Muestra el pico en dBFS y avisa si satura.
- **En segundo plano**: graba desde un servicio en primer plano, así que sigue con la pantalla apagada.
- **Sesiones**: cada grabación va a la sesión activa. Las sesiones se crean, renombran y borran, y las grabaciones se pueden mover de una a otra.
- **Servidor web**: con el wifi, la app muestra una dirección `http://<ip-del-móvil>:8080`. Desde el navegador del ordenador se escuchan las grabaciones, se descargan sueltas o se baja una sesión entera en ZIP.

## Cómo está hecho

- `RecorderService`: `AudioRecord` en coma flotante a 48 kHz, escrito a disco con `WavWriter` (PCM de 24 bit, cabecera corregida al cerrar).
- `WebServer`: servidor HTTP mínimo sobre `ServerSocket`, con peticiones `Range` para poder saltar en el reproductor del navegador.
- `Sessions`: carpetas dentro del almacenamiento de la app.

Requiere Android 9 (API 28) o superior.
