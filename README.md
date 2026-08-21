# RoosterPlanner

RoosterPlanner is een volledig lokaal roosterprogramma voor restaurantmanagers. Versie **0.10** voegt een zelfstandige Windows-app toe. De bestaande Android-app blijft beschikbaar als versie 0.6 en deelt dezelfde plannings- en ATW-logica.

## Windows v0.10 downloaden

1. Open op GitHub [**Actions → Build Windows v0.10**](https://github.com/mindlizzard/roosterandroid/actions/workflows/build-windows.yml).
2. Open de meest recente geslaagde run.
3. Download onder **Artifacts** het bestand `RoosterPlanner-Windows-v0.10`.
4. Gebruik `RoosterPlanner-0.10.0.exe` voor de installatie, of pak `RoosterPlanner-0.10-portable.zip` uit voor de draagbare versie.

De installer is automatisch door GitHub gebouwd en niet commercieel ondertekend. Windows kan daarom bij de eerste start een SmartScreen-melding tonen.

## Wat zit in v0.10?

- maandrooster in een brede matrix met volledige tijden, bijvoorbeeld `DAG 09:00-17:00`;
- labels **SETUP**, **DAG**, **TUSSEN** en **SLUIT** vet in scherm en PDF;
- automatische planning met ATW-controles en een afzonderlijke **Auto-fix**;
- handmatige diensten vastzetten en twee ingeplande diensten ruilen;
- ziekmelding invoeren en direct een geschikte vervanger zoeken;
- minimumbezetting per hele dag én per dagdeel;
- meerdere volledig gescheiden vestigingen in één werkbestand;
- eigen diensttemplates onbeperkt toevoegen, wijzigen, dupliceren en verwijderen;
- restauranttijden per weekdag, inclusief langere vrijdag-/zaterdagopening en gesloten dagen;
- vakantie, verlof, training en andere afwezigheid;
- terugkerende taken, tellingen, markers en dagnotities;
- PDF-rooster met loonadministratiepagina en CSV-export voor Excel;
- ongedaan maken/opnieuw uitvoeren, lichte/donkere modus en twintig lokale back-ups.

Alles wordt automatisch lokaal opgeslagen. Er is geen server, account of internetverbinding nodig.

## Eerste gebruik

1. Voeg op **Team** medewerkers toe en stel hun rollen en toegestane diensten in.
2. Vul de normale weekbeschikbaarheid in en voeg zo nodig uitzonderingen per datum toe.
3. Controleer op **Diensten & regels** de diensttemplates, restauranttijden en ATW-opties.
4. Voeg bij **Afwezig & bezetting** verlof en eventuele drukke dagdelen toe.
5. Klik **Genereer rooster**. Corrigeer handmatig of laat **Auto-fix** verder puzzelen.
6. Exporteer het eindresultaat op het tabblad **Rooster** als PDF of CSV.

Een handmatig vastgezette dienst krijgt in de matrix het label `VAST`. Auto-fix beschermt die diensten standaard en laat alleen een conflicterende vastzetting los als dat nodig is. Dit gedrag is instelbaar.

## Lokale bestanden en privacy

Op Windows staat het werkbestand in:

```text
%APPDATA%\RoosterPlanner\roosterplanner-v0.10.json
```

Voor iedere opslagwijziging wordt een back-up gemaakt; de laatste twintig staan in de map `backups`. Via **Bestand** kan een complete werkruimte of één vestiging als JSON worden geëxporteerd en later weer worden geïmporteerd.

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

Pauzes worden alleen als informatie gemeld; de app registreert niet of een pauze werkelijk is genomen. CAO-, jeugd-, zwangerschap- en sectorspecifieke uitzonderingen kunnen extra of afwijkende regels bevatten. Controleer een definitief rooster daarom altijd volgens de regels die voor de organisatie gelden.

## Zelf bouwen en testen

De Windows-module kan los van de Android SDK worden gebouwd met Java 17 en Gradle 8.9:

```powershell
gradle -p desktopApp clean test installDist
desktopApp\build\install\RoosterPlanner\bin\RoosterPlanner.bat
```

De workflow `.github/workflows/build-windows.yml` bouwt op een Windows-runner ook de draagbare app en de `.exe`-installer met `jpackage`. De desktopmodule hergebruikt de pure Kotlin-modellen, planner, analyses en ATW-validator uit de Android-module.

De Android-debug-APK wordt door `.github/workflows/build-apk.yml` gebouwd. Open het hoofdproject in Android Studio om de Android-app lokaal te ontwikkelen.
