# RoosterAndroid

Offline Android-app voor het maken en controleren van managerroosters.

## Eerste versie

- Jetpack Compose-interface voor telefoon.
- Managers, RM, trainees en leenmanagers toevoegen.
- Per persoon instellen of Setup, Dag, Tussen, Sluit en KPI toegestaan zijn.
- Per persoon maximum aantal diensten per week instellen.
- Beschikbaarheid per datum met optionele vroegste start, laatste eindtijd en vaste dienst.
- Diensttemplates en weekdagen volledig lokaal aanpassen.
- Automatische roosteropbouw: setup, tussenmanager op ingestelde drukke dagen en sluitmanager.
- Voorkeur voor twee opeenvolgende vrije dagen wordt gecontroleerd.
- Eerdere maanden blijven in lokale historie staan en worden meegenomen in controles.
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

Bij elke push naar `main` of `feature/initial-rooster-app` draait GitHub Actions. De workflow voert unit tests uit, bouwt `app-debug.apk` en uploadt deze als artifact `RoosterAndroid-debug`.

Lokaal kan het project in Android Studio worden geopend. De CI gebruikt Java 17, Android SDK 35 en Gradle 8.9.

## Status

Dit is versie `0.1.0`. Handmatig ruilen van twee reeds ingeplande diensten en uitgebreidere bezettingsregels per dagdeel staan nog op de vervolglijst.
