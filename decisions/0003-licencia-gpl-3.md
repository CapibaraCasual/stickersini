# ADR-0003: Licenciar el proyecto bajo GPL-3.0

- Estado: Aceptado
- Fecha: 2026-09-19

## Contexto

El proyecto es de código abierto y gratuito de forma deliberada. Su propuesta
de valor frente a las alternativas establecidas del mercado no es una función
exclusiva, sino el modelo: sin cuenta, sin anuncios, sin subida de contenido
(RNF-01, RNF-02).

Eso crea un riesgo concreto. Una licencia permisiva permite que cualquiera tome
el código, lo cierre, le añada publicidad y lo publique en la tienda. El clon
sería funcionalmente idéntico y competiría con la versión original sin ninguna
obligación de devolver nada.

El autor no busca ingresos del proyecto. Busca que exista y que no sea
capturado.

## Opciones consideradas

1. **MIT** — descartada: la más permisiva. No impone ninguna obligación al
   redistribuidor más allá de conservar el aviso de copyright. Es exactamente
   el escenario que se quiere evitar.
2. **Apache-2.0** — descartada: sería la elección por defecto y añade concesión
   expresa de patentes, pero igual que MIT permite cerrar un fork. La ventaja de
   patentes no compensa aquí.
3. **AGPL-3.0** — descartada: su cláusula adicional cubre el uso a través de una
   red. La aplicación es local y no tiene servidor (RNF-01), así que esa
   cláusula no aportaría nada y solo añadiría fricción.
4. **GPL-3.0** — elegida.

## Decisión

GPL-3.0 para todo el código propio.

La GPL no impide el fork: obliga a que cualquier versión distribuida a partir
de este código se publique también bajo GPL-3.0, con su código fuente
disponible. Un clon financiado por publicidad tendría que abrir su código, lo
que elimina la mayor parte del incentivo para hacerlo.

## Consecuencias

- Se pierde adopción potencial como biblioteca: nadie podrá integrar este
  código en una aplicación propietaria. Es aceptable porque el proyecto es un
  producto final, no una biblioteca.
- Todas las dependencias deben ser compatibles con GPL-3.0 (RNF-10). libwebp es
  BSD y lo es; cada dependencia nueva debe verificarse antes de añadirse.
- La licencia no se hace cumplir sola. Si aparece un clon, el autor tiene que
  detectarlo y reportarlo. La GPL da el fundamento para hacerlo; no da el
  trabajo hecho.
- La licencia cubre el código, no el nombre ni el ícono. Esa parte se trata por
  separado en `TRADEMARK.md`, que es lo que permite reclamar ante la tienda
  cuando un clon reutiliza la identidad visual.
- Nada de esto impide reescribir la aplicación desde cero imitando la idea. Eso
  es legal y no hay licencia que lo evite.
- Distribuir en Google Play bajo GPL-3.0 es posible. La aplicación es gratuita,
  así que no hay conflicto con los términos de la tienda.
