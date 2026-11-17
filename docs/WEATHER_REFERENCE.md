# Weather reference (from the desktop prototype)

The weather rules come from the desktop prototype at
[swapnilraj/notion-calendar-weather](https://github.com/swapnilraj/notion-calendar-weather), whose
source of truth is in `src/ui/` and `src/weather/`. This file copies every constant and rule an
implementer needs, so the Android port doesn't have to read TypeScript. If anything here disagrees
with that repo's `main`, the repo wins; fix this file.

## Open-Meteo request

```
GET https://api.open-meteo.com/v1/forecast
  ?latitude=<lat 2dp>&longitude=<lon 2dp>
  &hourly=temperature_2m,weather_code,precipitation_probability,precipitation,is_day
  &timezone=GMT&timeformat=unixtime
  &past_days=1&forecast_days=16
  &temperature_unit=celsius
```

- **Response:** `hourly.time[]` (unix seconds) plus parallel arrays of the same length.
- **Parsing:** reject any response where the lengths differ. `null` entries for probability or
  precipitation stay null.
- **Geocoding** (time-zone city fallback):
  `https://geocoding-api.open-meteo.com/v1/search?name=<city>&count=1&language=en&format=json`. Take
  `results[0].latitude/longitude`. A missing `results` means no match.
  - Getting the city from a zone ID: take the last path segment and replace `_` with a space:
    `America/New_York` → "New York".

## WMO code → condition

| Codes | Condition | Label |
|---|---|---|
| 0 / 1 | clear | Clear sky / Mainly clear |
| 2 | partly-cloudy | Partly cloudy |
| 3 | cloudy | Overcast |
| 45, 48 | fog | Fog |
| 51, 53, 55, 56, 57 | drizzle | Drizzle |
| 61, 63, 65, 66, 67 | rain | Rain |
| 80, 81, 82 | rain | Rain showers |
| 71, 73, 75, 77 | snow | Snow |
| 85, 86 | snow | Snow showers |
| 95, 96, 99 | thunderstorm | Thunderstorm |
| anything else | unknown | Unknown |

**Severity** (for day summaries): unknown −1, clear 0, partly-cloudy 1, cloudy 2, fog 3, drizzle 4,
rain 5, snow 6, thunderstorm 7.

## Bands

**Sky band:**

| Band | Condition |
|---|---|
| dark | `!isDay` or thunderstorm |
| bright | clear |
| mixed | partly-cloudy |
| overcast | anything else |

**Temperature band** (°C): freezing ≤ 0, cold ≤ 7, cool ≤ 14, mild ≤ 20, warm ≤ 27, hot > 27.

**Labels** (for the hour readout):
- Sky: Bright, Mixed, Overcast, Dark.
- Temperature: Freezing, Cold, Cool, Mild, Warm, Hot.

## Precipitation intensity (0–1)

```
wet conditions: rain = drizzle | rain | thunderstorm ; snow = snow ; anything else → 0
if precipMm != null:
    i = precipMm < 0.05 ? 0 : clamp(sqrt(precipMm / (snow ? 3 : 6)), 0.12, 1)
else:
    p = precipProbability ?? 50
    i = (p < 10 ? 0 : 0.15 + 0.85 * p/100) * (drizzle ? 0.6 : 1)
```

**Readout label** (phase 2):
- Snow: "Light snow" below 2 mm, otherwise "Heavy snow".
- Thunderstorm: "Thunderstorm".
- Drizzle: "Drizzle".
- Rain:

  | Amount | Label |
  |---|---|
  | unknown | "Rain" |
  | < 2.5 mm | "Light rain" |
  | < 7.6 mm | "Rain" |
  | otherwise | "Heavy rain" |

- Append " X.X mm" when the amount is at least 0.1 mm.

## Alignment

- **Precipitation stamps:** Open-Meteo stamps precipitation amount and probability at the **end** of
  the hour they cover.
- **Blocks:** hour block k (k→k+1) uses the sample at k+1.
- **Hour lines:** wetness at hour line i = mean of intensity(hours[i]) and intensity(hours[min(i+1, 24)]).
- **Instantaneous signals:** sky and temperature are instantaneous and sit on the lines.

## Tint (dark theme baseline; RGBA with alpha 0–1)

| Name | RGBA |
|---|---|
| SKY.bright | 255, 222, 140, 0.13 |
| SKY.mixed | 226, 212, 184, 0.08 |
| SKY.overcast | 148, 156, 168, 0.12 |
| SKY.dark | 38, 58, 120, 0.17 |
| FOG (if fog and not dark) | 198, 204, 212, 0.15 |
| STORM (thunderstorm) | 72, 52, 132, 0.20 |
| RAIN_SLATE | 78, 104, 150 |
| SNOW_PALE | 215, 232, 252 |

**Temperature cast** (rgb, k):

| Band | rgb | k |
|---|---|---|
| freezing | 120, 215, 255 | 0.55 |
| cold | 79, 190, 240 | 0.38 |
| cool | 95, 180, 200 | 0.15 |
| mild | — | 0 |
| warm | 255, 165, 80 | 0.25 |
| hot | 255, 105, 70 | 0.42 |

```
base = thunderstorm ? STORM : (fog && band != dark) ? FOG : SKY[band]
m = (band == dark) ? k * 0.6 : k
rgb = mix(base.rgb, cast.rgb, m); a = base.a + 0.05 * m
if rain > 0: rgb = mix(rgb, RAIN_SLATE, 0.55 * rain); a += 0.05 * rain
if snow > 0: rgb = mix(rgb, SNOW_PALE, 0.55 * snow); a += 0.04 * snow
light theme: r *= 0.82; g *= 0.82; b *= 0.90; a = min(0.30, a * 1.7)
```

The desktop values were tuned against a dark background of rgb(25,25,25). The light-theme values are
untested, so tune them on a phone and record the change here.

## Texture

| | Rain | Snow |
|---|---|---|
| Colour, dark theme | `rgba(170,205,255,0.32)` | `rgba(235,245,255,0.45)` |
| Colour, light theme | `rgba(40,90,190,0.30)` | `rgba(70,110,170,0.40)` |
| Tile | 12×18, paths `M9 1.5L6 9.5` and `M3.5 10.5L1 16.5` | 12×12, dots at (3,3) r1.1 and (9.5,9) r0.9 |

- **Rain strokes:** 1 wide, round caps.
- **Alpha per row:** colour alpha × the mask value, where the mask is interpolated between hour-block
  centres from the block intensities.
- **Dry days:** no layer at all.

## Day summary

- **Grouping:** group the hourly samples by local date. Skip days with fewer than 20 samples (edges of
  the data).
- **Basis:** the daytime hours if there are at least 3, otherwise all hours.
- **Notable conditions:** conditions with ≥ 2 hours in the basis (a thunderstorm needs only 1).
  - If any are notable, pick the most severe of them.
  - Otherwise, pick the most common.
- **High and low:** over all of the day's samples.
- **Rain chance:** the maximum non-null probability, shown in the header **only when the chosen
  condition is wet** (drizzle, rain, thunderstorm, snow).
