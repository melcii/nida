# NİDÂ

A quiet prayer times app for Android, with a home screen widget and a Qibla compass.

- **Simple view:** the next time and how long until it.
- **Compact view:** the whole day on one timeline: each prayer, the times to avoid prayer, Ishrâq, Duha, the night in thirds and halves with Seher, and a line for now. Tap any part for its times. Above it, every time of the day at a glance.
- **Detailed view:** Imsak, Sunrise, Dhuhr, Asr, Maghrib and Isha; the times to avoid voluntary prayer; and how far through the night you are, in thirds.
- **Qibla:** a compass that turns with your phone, the angle from North, and the distance to the Kaaba. It tells you when your location is off, weak or old, and shows a figure-8 to calibrate the compass.
- **Calendar:** pick any day to see its prayer times, with the Hijri date under every day.
- **Hijri date:** today's date in the Islamic calendar, moving to the next day at Maghrib.
- **Places:** your current location, plus any cities you add.
- **Widget:** the city, the six times, and the time left until the next one. You choose the background colour, how see-through it is, and the text colour.
- **Settings:** six common calculation methods, Standard or Hanafi Asr, English or Turkish, and **Update prayer times** to download the next two months from AlAdhan.

## Private by design

- **Online only when you ask.** NİDÂ connects to the internet only when you tap *Update prayer times* in Settings. It then sends each saved place's coordinates, rounded to about 1 km, with your method and Asr choice, to [AlAdhan](https://aladhan.com/prayer-times-api). Nothing else is sent, and nothing runs in the background.
- Without an update, or for days not downloaded, prayer times are calculated on the phone (the PrayTimes.org formulas, checked against AlAdhan in the tests).
- Town names and city search use a list built into the app, so your location is never looked up online.
- No cloud backup or device-to-device copy of the app's data.

## Light on battery

- Location is read once, only when you open the app or tap the city on the widget, using satellite GPS first. It never tracks you in the background.
- The widget redraws at the start of each minute only while the screen is on. With "Exact countdown" allowed in Settings, it ticks right on the minute.

## Sources

- Prayer time formulas: [PrayTimes.org](http://praytimes.org/), with methods as numbered by [AlAdhan](https://aladhan.com/calculation-methods)
- Hijri dates: the Umm al-Qura calendar, from Android's built-in ICU
- Towns and cities: [GeoNames](https://www.geonames.org/) (cities with over 5,000 people), [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)
- Font: [Inter](https://rsms.me/inter/), SIL Open Font License (see `licenses/`)
