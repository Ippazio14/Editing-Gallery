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

Il primo avvio mostra una spiegazione dell’accesso limitato e una schermata con caselle di spunta per DCIM, Pictures, Movies e le altre cartelle aggiunte dall’utente, come Download/festa. Non apre automaticamente il selettore Android. Toccare una casella non autorizzata apre la conferma Android per quella cartella; solo un risultato valido e persistito accende la spunta. Annullare il selettore lascia il menu utilizzabile. Aggiungi cartella permette di scegliere altri percorsi. Togliere una spunta revoca il relativo accesso e mantiene la riga disponibile per una nuova autorizzazione. Continua termina la configurazione e apre la griglia, anche se la selezione è vuota. Altro → Cartelle riapre lo stesso menu. Gli accessi ereditati da una cartella superiore restano validi.

La nuova MainActivity legge solo URI dello Storage Access Framework. Le cartelle compaiono allo stesso livello: le radici autorizzate restano anche vuote, con miniatura sfocata e conteggi a zero; le sottocartelle senza media diretti sono omesse, continuando a cercare nei loro discendenti. Percorso in alto a sinistra e conteggi in basso a destra sono sovrapposti alla miniatura, in bianco su nero, senza troncamento. Le cartelle con video hanno un bordo a pellicola. Fra gli elementi ci sono almeno 5 pixel di spazio. I conteggi includono solo i file direttamente nella cartella; sovrapposizioni fra accessi non duplicano gli album.

Nessun permesso generale READ_MEDIA, READ/WRITE_EXTERNAL_STORAGE o MANAGE_EXTERNAL_STORAGE, nemmeno dalle dipendenze. Disabilitati servizi, ricevitori e widget della vecchia scansione globale. Il selettore SAF concede accesso ai file della cartella intera: l'app visualizza soltanto immagini e video. Android 11+ impedisce la scelta della radice della memoria e di Download direttamente; si scelgono le sottocartelle di Download.

Apertura foto/video, editor fotografico esistente, condivisione e cancellazione confermata usano URI. La cancellazione in questa schermata è definitiva e il messaggio lo dichiara. Tenere premuto un media avvia la selezione multipla per Copia/Taglia; aprire poi una cartella e premere Incolla. Non sovrascrive file omonimi. Uno spostamento cancella l'originale solo dopo la chiusura corretta degli stream della copia. I fallimenti sono segnalati e restano negli appunti per un eventuale nuovo tentativo.

La vecchia MainActivity è conservata come LegacyMainActivity non registrata nel manifest. Questa prima migrazione introduce una schermata e un visualizzatore SAF essenziali; Cerca e Altro riutilizzano il componente Fossify. La ricerca filtra percorsi e nomi dei media già letti dalle sole cartelle autorizzate. Altro include cartelle, ordinamento, colonne, aggiornamento e informazioni; cestino e preferenze generali non sono collegati alla nuova schermata. Il selettore esterno restituisce un singolo media, filtrato per MIME; la selezione multipla per app esterne non è ancora implementata.

Test unitari: radice vuota mantenuta e sottocartelle vuote omesse, radici annidate esplicitamente autorizzate, discendenti profondi e conteggi locali, accessi sovrapposti/cicli, errori di accesso senza scansione esterna, nessun accesso e cancellazione del lavoro. Verificare su dispositivo il selettore e le revoche, riavvio, file nuovi, font grandi e rotazione, editor, video e trasferimenti, anche da SD e con accesso in sola lettura.


### Correzione del flusso di avvio

La scansione dei media parte solo dopo la chiusura della schermata di selezione; il risultato del selettore non avvia scansioni concorrenti in aggiunta a onResume. Il caricamento non interroga più i document provider sul thread grafico per ottenere i nomi delle cartelle. Il riepilogo delle spunte usa gli accessi persistenti effettivi, quindi un accesso revocato da Android non appare autorizzato. La nuova schermata compare una volta anche aggiornando dalla prima versione SAF, senza perdere gli accessi esistenti.

I test Robolectric coprono introduzione e caselle prima di qualsiasi selettore di sistema, annullamento, autorizzazione e Continua, righe Download personalizzate e revoca, accesso perso, ritorno alla galleria e avvii successivi. Le dipendenze di test non entrano nell’APK.

I test della griglia controllano le sovrapposizioni agli angoli opposti, i font grandi, il riuso delle celle e la spaziatura minima. La ricerca viene verificata senza nuove scansioni del provider.
