# Prototipo editor accessibile

Aprire una foto dalla galleria mostra tre azioni: Modifica, Condividi, Elimina. Modifica apre il nuovo editor fotografico.

- Anteprima sopra i controlli in verticale; a destra nei layout orizzontali larghi almeno 680 dp.
- Undo, Redo e Auto in alto a sinistra sull’anteprima. Ritaglia, Ridimensiona, Ruota e Casuale in alto a destra.
- Saturazione, temperatura, luminosità e contrasto con pulsanti −/+ e valore leggibile.
- Ritaglio tramite CanHub; rotazione a passi di 90°. Ridimensionamento proporzionale per larghezza e qualità JPEG da 1 a 100.
- Auto analizza la distribuzione luminosa per proporre luminosità e contrasto moderati. Ripeterlo non accumula correzioni.
- Cronologia fino a 50 modifiche, memorizzata come parametri senza duplicare le immagini originali.
- Salva copia usa il selettore Android e genera un JPEG. L’originale non viene scritto.

## Limiti di questa prima versione

Esportazione JPEG SDR; trasparenza appiattita sul bianco. Metadati EXIF non ricopiati. Lettura delle foto fino a 40 megapixel, subordinata alla memoria disponibile. I pulsanti −/+ avanzano di cinque unità per tocco. La stima del peso prima del salvataggio e l’editor video nuovo non fanno ancora parte del prototipo.

## Rimozione preferiti

Rimossi modello, DAO, proprietà del modello multimediale, query, logica di scansione, album virtuale, contrassegni, menu e importazione/esportazione. La migrazione v10→v11 elimina tabella e colonna obsolete conservando media e cestino. Restano soltanto riferimenti di migrazione/bonifica delle installazioni precedenti. Le costanti della dipendenza condivisa Fossify Commons non sono modificate.

## Verifiche

`python tools/check_favorites_migration.py` controlla la conservazione dei dati e dei vincoli nel database. La build GitHub compila l’APK. Verificare sul dispositivo: apertura, tre pulsanti, rotazione schermo, ogni strumento, Undo/Redo, salvataggio e ritorno alla galleria. Provare prima con copie di foto.
