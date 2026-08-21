# RoosterAndroid

Offline Android-app voor het maken en controleren van managerroosters.

## Versie 0.10

Nieuw in v0.10:

- De app heeft een eigen lichte en donkere stijl, rustigere navigatie, duidelijke statusmeldingen en beter herkenbare overzichts- en waarschuwingskaarten.
- **Wis maand** vraagt voortaan om bevestiging en wist uitsluitend de getoonde maand van de actieve vestiging; andere maanden, vestigingen en vervangingsverzoeken blijven behouden.
- Lokale roosterdata worden via een tijdelijk bestand atomair opgeslagen, zodat een onderbroken schrijfactie het laatst opgeslagen rooster niet beschadigt.
- Ongeldige geïmporteerde diensttijden worden duidelijk geweigerd; afwijkende of ontbrekende roosterdata leveren een controlefout op in plaats van een crash.
- De maandmatrix gebruikt vooraf opgebouwde zoekindexen en de bezettingsberekening hergebruikt dienstintervallen, waardoor grote teams merkbaar soepeler scrollen en verversen.
- Dag kopiëren controleert al in het venster of beide datums bij de getoonde maand horen.

Nieuw in v0.9:

- Matrixcellen zijn breder en hoger; de klokregel gebruikt een vaste, smalle opmaak zodat tijden zoals `09:00-17:00` ook bij een grotere Android-lettergrootte volledig zichtbaar blijven.
- Een volledige roosterdag naar een andere datum binnen dezelfde maand kopiëren.
- Geselecteerde vakken leegmaken zonder ze als vaste vrije dag te blokkeren.
- Het slotniveau van meerdere bestaande diensten tegelijk wijzigen naar vast, voorkeur of vrij voor de solver.

Nieuw in v0.8:

- Roosterwijzigingen ongedaan maken, opnieuw uitvoeren, als herstelpunt bewaren of in één keer terugdraaien.
- Drie niveaus per dienst: **vast** (Auto-fix mag niets wijzigen), **voorkeur** (alleen verplaatsen als dat echt nodig is) en **vrij voor solver**.
- Meerdere roostervakken selecteren en in één handeling een dienst of vaste vrije dag geven.
- Een hele week naar een andere week kopiëren; conflicterende cellen worden veilig overgeslagen.
- Auto-fix puzzelt in een achtergrondtaak verder, toont fase en voortgang en kan worden geannuleerd.
- Een managementdashboard met open vervangingen, ziekte, ATW-fouten, leenmanagers, contracturen en open plannerpunten.
- Een bezettingsheatmap per ochtend, middag, avond en nacht met ingepland/minimaal.
- Afwijkende openingstijden per datum voor feestdagen, evenementen, een langere avond, sluiting of tijdelijke 24-uursopening.
- Datum- en 24-uurs-tijdkiezers in plaats van foutgevoelige vrije invoer.
- Roostercellen tonen **SETUP**, **TUSSEN**, **SLUIT** en andere diensttypen volledig vet, met normale tijden zoals `09:00-17:00` eronder.

Verder aanwezig:

- Meerdere vestigingen beheren en per vestiging een eigen team en rooster tonen.
- Openingstijden per weekdag, inclusief gesloten dagen, late sluiting en 24-uursvestigingen.
- Passende Setup-, Dag-, Tussen-, Sluit- en Nachtdiensten voorstellen vanuit de openingstijden.
- Onbeperkt eigen diensttemplates maken en bestaande templates aanpassen, met tijden en actieve weekdagen per vestiging.
- Managers, RM, trainees en leenmanagers aan één of meer vestigingen koppelen.
- Per persoon instellen welke diensten zijn toegestaan.
- Per persoon maximum aantal diensten per week instellen.
- Beschikbaarheid per datum met optionele vroegste start, laatste eindtijd en vaste dienst.
- Ziekte en andere goedgekeurde afwezigheid halen geplande diensten uit het rooster en maken open vervangingsverzoeken.
- Vervangers kiezen uit beschikbare eigen of leenmanagers, gesorteerd op inzet en gecontroleerd op beschikbaarheid en ATW.
- Twee geplande diensten handmatig ruilen, ook tussen verschillende dagen, met ATW-controle en ruilhistorie.
- Ieder roostervak handmatig bewerken of als vaste vrije dag blokkeren.
- Na een handmatige wijziging controleert de app het hele rooster en toont zij zo mogelijk een concreet herstelvoorstel dat eerst kan worden beoordeeld.
- De knop **Auto-fix** voert na veel handmatige wijzigingen een uitgebreidere zoekronde uit en bewaart handmatige keuzes waar mogelijk.
- Minimumbezetting per terugkerend dagdeel en eenmalige minimumbezetting per datum instellen.
- Automatische locatiebewuste roosteropbouw die openingstijden, dagdelen, contracten, beschikbaarheid en bezetting combineert.
- Medewerkers worden niet dubbel ingepland wanneer zij voor meerdere vestigingen werken.
- Voorkeur voor twee opeenvolgende vrije dagen wordt gecontroleerd.
- Eerdere maanden blijven in lokale historie staan en worden meegenomen in controles.
- PDF-rooster en loonadministratie worden voor de actieve vestiging opgebouwd.
- JSON import/export via Android document picker.
- Geen server, account of cloudopslag nodig.

## ATW-controles

De validator is bedoeld voor de algemene Nederlandse Arbeidstijdenwet voor werknemers van 18 jaar en ouder. Hij controleert onder andere:

- maximaal 12 uur per dienst;
- maximaal 60 uur per week;
- gemiddeld maximaal 55 uur per week over 4 weken;
- gemiddeld maximaal 48 uur per week over 16 weken;
- standaard minimaal 11 uur dagelijkse rust;
- wekelijkse rust: 36 uur aaneengesloten of de 72-uursvariant over 14 dagen;
- nachtdienstherkenning en extra nacht-rustregels;
- aantallen nachtdiensten en gemiddeld aantal uren bij regelmatig nachtwerk;
- instelbaar maximum opeenvolgende werkdagen;
- waarschuwing wanneer onvoldoende historische roosterdata aanwezig is.

Pauzes worden als informatie gemeld. De app registreert in deze versie niet of een pauze daadwerkelijk is genomen. CAO-, jeugd-, zwangerschap- en sectorspecifieke uitzonderingen kunnen extra of afwijkende regels bevatten.

## APK bouwen

Bij elke push naar `main` of een `feature/**`-branch draait GitHub Actions. De workflow voert unit tests en Android Lint uit, bouwt `app-debug.apk` en uploadt deze als artifact `RoosterAndroid-debug`.

Lokaal kan het project in Android Studio worden geopend. De CI gebruikt Java 17, Android SDK 35 en Gradle 8.9.

## Status

Dit is versie `0.10.0` (`versionCode 12`). Bestaande v0.6-, v0.7-, v0.8- en v0.9-gegevens blijven bruikbaar: ontbrekende vestigings-, uitzonderings- en slotgegevens krijgen bij het laden veilige standaardwaarden.
