-- Seed 9 TIAM catalog exercises (owner_id NULL = TIAM platform)
INSERT INTO exercises (title, description, instructions, difficulty, material_type, status, owner_id) VALUES
('Secuencia de números con atención dividida',
 'Ejercicio de atención dividida utilizando secuencias numéricas.',
 'El paciente debe repetir secuencias de números mientras realiza otra tarea simultáneamente. Comience con series de 3 números y aumente progresivamente.',
 'INTERMEDIATE', 'PRINTABLE', 'PUBLISHED', NULL),

('Evocación de palabras por categoría',
 'Ejercicio de fluencia verbal para evocar palabras dentro de una categoría semántica.',
 'Pida al paciente que diga la mayor cantidad de palabras posibles de una categoría (animales, frutas, herramientas) en 60 segundos. Registre el total.',
 'BASIC', 'VERBAL', 'PUBLISHED', NULL),

('Reconocimiento de objetos cotidianos',
 'Ejercicio de reconocimiento visual de objetos del entorno diario.',
 'Muestre imágenes de objetos cotidianos al paciente y solicite que los identifique y describa su uso. Incluya objetos familiares y algunos menos comunes.',
 'BASIC', 'IMAGE_SEQUENCE', 'PUBLISHED', NULL),

('Rompecabezas de mapa ciudad',
 'Ejercicio de orientación espacial mediante reconstrucción de mapas.',
 'Entregue las piezas de un mapa de ciudad recortado y solicite al paciente que las reorganice correctamente. Comience con sectores grandes y reduzca el tamaño.',
 'INTERMEDIATE', 'PRINTABLE', 'PUBLISHED', NULL),

('Torre de Hanoi simplificada',
 'Ejercicio de planificación y resolución de problemas con discos.',
 'Use 3 discos y 3 varillas. El paciente debe mover todos los discos a la varilla destino siguiendo las reglas: mover un disco a la vez, nunca un disco mayor sobre uno menor.',
 'ADVANCED', 'SENSORIAL', 'PUBLISHED', NULL),

('Secuencia de movimientos manos',
 'Ejercicio de praxias ideomotoras con secuencias gestuales.',
 'Demuestre una secuencia de 3-5 movimientos de manos y solicite al paciente que la repita. Aumente la complejidad progresivamente. Corrija errores de manera inmediata.',
 'INTERMEDIATE', 'SENSORIAL', 'PUBLISHED', NULL),

('Memoria inmediata con objetos',
 'Ejercicio de memoria a corto plazo con objetos físicos.',
 'Coloque 5-7 objetos sobre la mesa, déjelos 30 segundos, cúbralos y solicite al paciente que mencione todos los que recuerda. Registre aciertos y errores.',
 'BASIC', 'SENSORIAL', 'PUBLISHED', NULL),

('Estimulación táctil con texturas',
 'Ejercicio de estimulación sensorial mediante reconocimiento táctil.',
 'Prepare bolsas con materiales de distintas texturas (liso, rugoso, suave, duro). El paciente, con ojos cerrados, debe identificar cada textura y describirla.',
 'BASIC', 'SENSORIAL', 'PUBLISHED', NULL),

('Completar oraciones con palabras clave',
 'Ejercicio de fluencia verbal y memoria semántica mediante completado de oraciones.',
 'Lea oraciones incompletas en voz alta y solicite al paciente que las complete con la palabra más apropiada. Use contextos familiares y cotidianos.',
 'INTERMEDIATE', 'VERBAL', 'PUBLISHED', NULL);

-- Associate exercises with cognitive areas
-- Exercise 1: atencion + funciones-ejecutivas
INSERT INTO exercise_cognitive_areas (exercise_id, cognitive_area_id)
SELECT e.id, ca.id FROM exercises e, cognitive_areas ca
WHERE e.title = 'Secuencia de números con atención dividida' AND ca.slug IN ('atencion','funciones-ejecutivas');

-- Exercise 2: fluencia-verbal + memoria
INSERT INTO exercise_cognitive_areas (exercise_id, cognitive_area_id)
SELECT e.id, ca.id FROM exercises e, cognitive_areas ca
WHERE e.title = 'Evocación de palabras por categoría' AND ca.slug IN ('fluencia-verbal','memoria');

-- Exercise 3: agnosias
INSERT INTO exercise_cognitive_areas (exercise_id, cognitive_area_id)
SELECT e.id, ca.id FROM exercises e, cognitive_areas ca
WHERE e.title = 'Reconocimiento de objetos cotidianos' AND ca.slug = 'agnosias';

-- Exercise 4: orientacion-espacial
INSERT INTO exercise_cognitive_areas (exercise_id, cognitive_area_id)
SELECT e.id, ca.id FROM exercises e, cognitive_areas ca
WHERE e.title = 'Rompecabezas de mapa ciudad' AND ca.slug = 'orientacion-espacial';

-- Exercise 5: funciones-ejecutivas
INSERT INTO exercise_cognitive_areas (exercise_id, cognitive_area_id)
SELECT e.id, ca.id FROM exercises e, cognitive_areas ca
WHERE e.title = 'Torre de Hanoi simplificada' AND ca.slug = 'funciones-ejecutivas';

-- Exercise 6: praxias
INSERT INTO exercise_cognitive_areas (exercise_id, cognitive_area_id)
SELECT e.id, ca.id FROM exercises e, cognitive_areas ca
WHERE e.title = 'Secuencia de movimientos manos' AND ca.slug = 'praxias';

-- Exercise 7: memoria
INSERT INTO exercise_cognitive_areas (exercise_id, cognitive_area_id)
SELECT e.id, ca.id FROM exercises e, cognitive_areas ca
WHERE e.title = 'Memoria inmediata con objetos' AND ca.slug = 'memoria';

-- Exercise 8: estimulacion-sensorial + agnosias
INSERT INTO exercise_cognitive_areas (exercise_id, cognitive_area_id)
SELECT e.id, ca.id FROM exercises e, cognitive_areas ca
WHERE e.title = 'Estimulación táctil con texturas' AND ca.slug IN ('estimulacion-sensorial','agnosias');

-- Exercise 9: fluencia-verbal + memoria
INSERT INTO exercise_cognitive_areas (exercise_id, cognitive_area_id)
SELECT e.id, ca.id FROM exercises e, cognitive_areas ca
WHERE e.title = 'Completar oraciones con palabras clave' AND ca.slug IN ('fluencia-verbal','memoria');
