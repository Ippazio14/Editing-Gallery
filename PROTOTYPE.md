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

## Navigazione semplificata

Rimossi filtro dei tipi di file, visibilità temporanea di nascosti/esclusi e avvio fotocamera (menu, preferenze e codice). La schermata iniziale mostra solo le cartelle; la vecchia preferenza per la vista globale viene ignorata. La ricerca globale e l’ordinamento dentro le cartelle restano disponibili. Le restrizioni dei selettori esterni di immagini/video restano operative.


## Cartelle autorizzate e griglia (ramo scoped-folder-access)

Il primo avvio apre il selettore Android di cartelle. Annullando si resta sulla pagina vuota con il pulsante Cartelle. Cartelle → Aggiungi cartella aggiunge un accesso persistente; lo stesso menu consente di revocarlo senza eliminare i file. Gli accessi ereditati da un'altra cartella superiore restano validi.

La nuova MainActivity legge solo URI dello Storage Access Framework. Ogni cartella autorizzata e ogni discendente, inclusi quelli vuoti, compare nella griglia allo stesso livello. Un padre con quattro sottocartelle produce cinque album. Percorso e conteggi separati di immagini/video sono sopra la miniatura, in bianco su nero, senza troncamento. I conteggi includono solo i file direttamente nella cartella; sovrapposizioni fra accessi non duplicano gli album.

Nessun permesso generale READ_MEDIA, READ/WRITE_EXTERNAL_STORAGE o MANAGE_EXTERNAL_STORAGE, nemmeno dalle dipendenze. Disabilitati servizi, ricevitori e widget della vecchia scansione globale. Il selettore SAF concede accesso ai file della cartella intera: l'app visualizza soltanto immagini e video. Android 11+ impedisce la scelta della radice della memoria e di Download direttamente; si scelgono le sottocartelle di Download.

Apertura foto/video, editor fotografico esistente, condivisione e cancellazione confermata usano URI. La cancellazione in questa schermata è definitiva e il messaggio lo dichiara. Tenere premuto un media avvia la selezione multipla per Copia/Taglia; aprire poi una cartella e premere Incolla. Non sovrascrive file omonimi. Uno spostamento cancella l'originale solo dopo la chiusura corretta degli stream della copia. I fallimenti sono segnalati e restano negli appunti per un eventuale nuovo tentativo.

La vecchia MainActivity è conservata come LegacyMainActivity non registrata nel manifest. Questa prima migrazione introduce una schermata e un visualizzatore SAF essenziali; i vecchi menu di ordinamento, ricerca globale, cestino e preferenze generali non sono collegati alla nuova schermata. Il selettore esterno restituisce un singolo media, filtrato per MIME; la selezione multipla per app esterne non è ancora implementata.

Test unitari: cartella padre + quattro figli vuoti, discendenti profondi e conteggi locali, accessi sovrapposti/cicli, errori di accesso senza scansione esterna, nessun accesso e cancellazione del lavoro. Verificare su dispositivo il selettore e le revoche, riavvio, file nuovi, font grandi e rotazione, editor, video e trasferimenti, anche da SD e con accesso in sola lettura.
