# RejTag · Android

RejTag to eksperyment z odnajdywaniem znajomych w przestrzeni. Ten projekt jest **prototypem**, a nie gotowym systemem lokalizacji 1:1 ani usługą gotową do wdrożenia publicznego.

## Co działa w tej wersji

- Android pobiera lokalizację telefonu, gdy aplikacja jest otwarta i użytkownik udzielił uprawnienia.
- Użytkownik może świadomie wysłać **jednorazową migawkę** swojej pozycji przez systemowy arkusz udostępniania. Znajomy wkleja kod w RejTag. Kod zawiera współrzędne, czas i szacowaną dokładność. RejTag przestaje przyjmować kod po 5 minutach, ale odbiorca może zachować lub skopiować współrzędne. Traktuj je jak wrażliwą informację.
- Wizjer wyświetla obraz tylnej kamery (lub działa bez obrazu, gdy kamera jest niedostępna) oraz orientacyjny kierunek i odległość w linii prostej obliczone z lokalizacji i kompasu. To **nie jest przestrzenny znacznik AR ani pomiar telefonu przez ścianę**. Kompas należy skalibrować, a ekran ostrzega, gdy dane nie wystarczają do wyznaczenia kierunku.
- Aplikacja sprawdza możliwości telefonu. Kierunek pochodzi z wektora rotacji lub, jeśli go nie ma lub jest niewiarygodny, z akcelerometru i magnetometru. Bez kompasu nadal wyświetla przybliżoną odległość, jeśli obie lokalizacje są dostępne i aktualne. Niedokładny lub nieaktualny pomiar nie jest przedstawiany jako pewny kierunek.
- W razie braku kamery/kompasu można otworzyć pozycję znajomego w zewnętrznej aplikacji mapowej (dopiero po potwierdzeniu przekazania jej współrzędnych) albo wyświetlić i skopiować współrzędne. Mapa jest dodatkową aplikacją i nie musi być zainstalowana. Same współrzędne są dostępne bez niej. Pozycja musi być aktualna.
- Ekran „Możliwości telefonu” pokazuje wykryty sprzęt i stan uprawnień. Wykrycie UWB, Bluetooth i Wi-Fi nie oznacza, że pomiar z nich działa — w tym prototypie nie jest jeszcze zaimplementowany.
- Ręczne kody i sesja logowania pozostają w pamięci procesu. Ręczny tryb nie wymaga serwera. Opcjonalny, skonfigurowany tryb na żywo używa Supabase; szczegóły i ograniczenia poniżej.

Dostępność i dokładność GPS, kompasu i kamery zależą od telefonu oraz otoczenia. W budynkach, przy zakłóceniach magnetycznych, po zmianie pozycji znajomego lub po zasłonięciu nieba wyniki mogą być znacznie błędne. Nie używaj prototypu do bezpieczeństwa lub nawigacji w ruchu.

## Budowanie APK

Wymagane: JDK 17, Android SDK z platformą 36, dostęp do repozytoriów Google i Gradle. W Android Studio otwórz katalog projektu i zsynchronizuj Gradle lub uruchom:

```sh
./gradlew assembleDebug
```

Plik powstanie pod `app/build/outputs/apk/debug/app-debug.apk`. To **debug APK**, do testów, nie wersja sklepowa. Na GitHub Actions workflow `Android debug APK` również buduje i udostępnia go jako artefakt `RejTag-debug-apk` po uruchomieniu workflow na wypchniętym branchu.

## Eksperymentalny tryb na żywo

Za zgodą właściciela prototyp korzysta z tego samego projektu Supabase i **tych samych kont RejHub**. Zaloguj się nazwą konta oraz hasłem lub istniejącym PIN-em RejHub. Aplikacja tworzy osobną sesję, a nie przejmuje sesji z przeglądarki. Hasło i token sesji nie są zapisywane w pamięci trwałej urządzenia. Nie zmieniono logowania ani strony RejHub.

Migracje `20261113224431_rejtag_live_prototype` i `20261113224942_rejtag_rejhub_accounts` zostały zastosowane w projekcie. Druga zastępuje poprzedni eksperymentalny wariant z Supabase Auth po sprawdzeniu, że tabele RejTag są puste; każda funkcja weryfikuje sesję przez `community_session_user`. Tabele RejTag pozostają prywatne, a stare punkty końcowe wyłączono. Strona i istniejące funkcje logowania nie zostały zmienione. Nie stosuj tych migracji ponownie w tym projekcie. Ręczny tryb nadal działa bez serwera.

Domyślna konfiguracja klienta w `supabase-public.properties` wskazuje projekt RejHub i wykorzystuje jego **publiczny klucz anon**, opublikowany także w kodzie strony. Klucz nie jest tajny i będzie widoczny w APK; zabezpieczeniem są uwierzytelnianie oraz uprawnienia w bazie. Testowy projekt można wskazać w ignorowanym `local.properties` (`SUPABASE_URL` i `SUPABASE_ANON_KEY`) lub przez zmienne środowiskowe. Nigdy nie umieszczaj w aplikacji klucza `service_role`, klucza `secret` ani hasła bazy.

1. Zbuduj i zainstaluj APK na obu telefonach. W „Połącz ze znajomym” zaloguj się kontem RejHub i wyślij jednorazowe zaproszenie. Nowe konto można założyć na stronie RejHub. W menu znajomego udostępnianie włącza się osobno i jawnie. Druga osoba musi wybrać „Pokaż pozycję znajomego”.
2. Aplikacja pobiera i wysyła najnowszą pozycję co około 15 sekund **tylko przy otwartej aplikacji**. Zapytanie rozpoczęte przed przejściem do tła może jeszcze się zakończyć. To nie jest usługa działająca w tle. Gdy brakuje połączenia, lokalizacji lub zgody znajomego, wizjer przestaje pokazywać aktualny punkt; pomiary starsze niż 2 minuty nie są udostępniane.
3. Zgoda działa do jej cofnięcia: w menu znajomego wyłącz udostępnianie, usuń znajomego albo wybierz „Zatrzymaj wszystkie udostępnienia”. Sprawdź potwierdzenie serwera — bez internetu zatrzymanie może się nie powieść. Wylogowanie po potwierdzeniu serwera wyłącza wszystkie Twoje udostępnienia i unieważnia tylko sesję RejTag, pozostawiając inne sesje RejHub bez zmian. Przy braku internetu wylogowanie i zatrzymanie nie zostaną potwierdzone; można wtedy usunąć sesję lokalnie, ale zgoda na serwerze może pozostać włączona. Aby ją cofnąć, trzeba ponownie się zalogować i zatrzymać udostępnianie. Sesja jest tylko w pamięci i może wygasnąć (wtedy trzeba zalogować się ponownie).

Kod zaproszenia jest jednorazowy i ważny 24 godziny; osoba posiadająca go może przyjąć zaproszenie. Dodanie znajomego nie włącza udostępniania. Serwer przechowuje tylko ostatni pomiar, a nie historię. Po wyłączeniu wszystkich udostępnień pozycja jest usuwana; przy pozostawionej zgodzie ostatni pomiar może pozostać w bazie, choć po 2 minutach nie jest zwracany. Skopiowanych lub wcześniej odebranych współrzędnych nie da się cofnąć.

**Przed publicznym udostępnieniem**, szczególnie nastolatkom, potrzebne są m.in. testy na urządzeniach i pełny przegląd bezpieczeństwa, mechanizmy blokowania i zgłaszania, ograniczanie nadużyć, automatyczna retencja i polityka prywatności. Sprawdzono obecność obiektów i podstawowe uprawnienia w projekcie Supabase, ale nie przeprowadzono jeszcze pełnego testu integracyjnego z rzeczywistymi kontami ani testu na telefonach. Nie zmieniaj globalnych ustawień uwierzytelniania bez oceny wpływu na inne aplikacje.

### Test logiki bazy lokalnie

Testy używają lokalnej instancji PostgreSQL WASM, bez dostępu do konta Supabase:

```sh
npm install --prefix /tmp/rejtag-test @electric-sql/pglite@0.5.16
node scripts/test-live-schema.mjs
node scripts/test-rejhub-accounts.mjs
```

Testują m.in. zaproszenia, weryfikację i unieważnianie sesji, brak dostępu osób postronnych, zgodę na udostępnianie i jej cofnięcie. Test kont używa symulowanego kontraktu sesji RejHub, nie prawdziwych kont. Nie zastępują testu integracyjnego Supabase ani testów na Androidzie.

## Test na dwóch telefonach

1. Zainstaluj debug APK na obu Androidach (Android 8.0 lub nowszy).
2. Na obu telefonach włącz lokalizację oraz udziel jej aplikacji. Aby korzystać z wizjera, udziel też dostępu do kamery.
3. Na telefonie znajomego wybierz „Wyślij moją pozycję” i przekaż kod zaufanej osobie.
4. Na drugim telefonie wklej wiadomość z kodem w „Wklej kod znajomego” i otwórz wizjer. Trzymaj telefon pionowo, skierowany przed siebie.
5. Jeśli nie widzisz kierunku, przeczytaj komunikat, sprawdź „Możliwości telefonu” lub użyj mapy/współrzędnych.
6. Aby odświeżyć pozycję znajomego, poproś go o wysłanie nowego kodu. Po pięciu minutach kod wygasa; wskazanie przestaje działać.

## Kierunek dalszych prac

1. Zmierzyć zachowanie GPS, kompasu i kamery na konkretnych modelach w terenie i wewnątrz budynków.
2. Dopracować i przetestować eksperymentalne udostępnianie między kontami: jawny status aktywności, szybkie wstrzymanie i wycofanie zgody, blokowanie, limity retencji oraz bezpieczeństwo niepełnoletnich. Działanie w tle wymaga osobnego projektu zgodnego z ograniczeniami Androida; obecny prototyp tego nie robi.
3. Zweryfikować na rzeczywistych urządzeniach Android UWB: dostępność sprzętu, odległość, ewentualny kierunek i zachowanie przy przeszkodach. Zaprojektować wymianę parametrów sesji oraz łączenie pomiarów z ARCore / IMU. UWB i ARCore nie są jeszcze zaimplementowane. Bluetooth i Wi-Fi mogą wspomagać komunikację i pomiar, ale RSSI samo w sobie nie zapewnia położenia 3D.
4. Oceniać jakość danych i pokazywać niepewność; nie przedstawiać przybliżenia jako precyzyjnego położenia. Badania wykrywania osób za przeszkodami wymagają innego sprzętu i osobnej oceny bezpieczeństwa oraz zgody uczestników.
