# NetLatch

Lag switch local para Free Fire en Android. Sin root, sin servidor externo.
Tuneliza solo el tráfico del juego y descarta UDP del servidor en ráfagas cortas.

## Compilar

El repo ya trae un workflow de GitHub Actions. Al hacer push a `main` se compila
el APK automáticamente.

1. Pestaña Actions.
2. Abre la ejecución más reciente.
3. Descarga el artefacto `NetLatch-apk` al final de la página.
4. Descomprime y instala el `.apk` con orígenes desconocidos activado.

## Uso

1. Abre la app y pulsa "Activar túnel". Acepta el diálogo de VPN.
2. Excluye la app de la optimización de batería.
3. Entra a Free Fire. El contador de endpoints debe subir.
4. Pulsa "Burbuja flotante" y concede el permiso de superposición.
5. Toca el círculo para cortar. Mantenlo pulsado para quitarlo.

## Notas

- Perfil medio (400-600 ms) es el punto de partida razonable.
- Si el contador se queda en 0 dentro de la partida, tu ROM restringe
  `/proc/net/udp` y se usa el fallback por rango de puertos.
