# NİDÂ

A quiet prayer times app for Android, with a home screen widget and a Qibla compass.

- **Simple view:** the next time and how long until it.
- **Detailed view:** Imsak, Sunrise, Dhuhr, Asr, Maghrib and Isha; the times to avoid voluntary prayer; and how far through the night you are, in thirds.
- **Qibla:** a compass that turns with your phone, the angle from North, and the distance to the Kaaba.
- **Hijri date:** today's date in the Islamic calendar, moving to the next day at Maghrib.
- **Places:** your current location, plus any cities you add.
- **Widget:** the city, the six times, and the time left until the next one. You choose the background colour, how see-through it is, and the text colour.
- **Settings:** six common calculation methods, Standard or Hanafi Asr, English or Turkish.

## Light on battery

- Location is read once, only when you open the app or tap the city on the widget. It never tracks you in the background.
- Prayer times are downloaded about two months at a time and kept on the phone, so the widget needs no GPS or internet.
- The widget redraws once a minute only while the screen is on.

## Sources

- Prayer times and Hijri dates: [AlAdhan API](https://aladhan.com/prayer-times-api)
- City search: [Open-Meteo Geocoding](https://open-meteo.com/en/docs/geocoding-api)
- Font: [Inter](https://rsms.me/inter/), SIL Open Font License (see `licenses/`)
