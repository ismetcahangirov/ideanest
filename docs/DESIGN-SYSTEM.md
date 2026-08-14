# Dizayn və Hərəkət (Motion) Sistemi

**Referans:** [nixtio.com](https://nixtio.com) — 14 Avqust 2026 tarixində brauzerdə (Playwright) canlı analiz edilib: CSSOM, keyframe-lər, transition tokenləri, JS bundle imzaları.

**Məqsəd:** IdeaNest platformasının **hərəkət (motion) sistemi**. Bu sənəd `ARCHITECTURE.md`-ni tamamlayır.

> ⚠️ **Vacib — rəng və səth sistemi dəyişib.**
> Bu sənədin **§5 (Vizual dil)**, **§5.3 (radius)** və **§7.4 (rəng tokenləri)** bölmələri Nixtio-nun **açıq temasına** əsaslanırdı və **artıq qüvvədə deyil**.
> Faktiki rəng, səth, radius və komponent sistemi: **[`UI-KIT.md`](./UI-KIT.md)** — qara fon + lime vurğu.
> Bu sənədin **hərəkət hissəsi (§1–4, §7.3, §8, §9) tam qüvvədədir** və `UI-KIT.md` ilə birlikdə oxunmalıdır.

---

## Mündəricat

1. [Analizin xülasəsi](#1-analizin-xülasəsi)
2. [Hərəkət kitabxanaları (faktiki stack)](#2-hərəkət-kitabxanaları-faktiki-stack)
3. [Hərəkət tokenləri](#3-hərəkət-tokenləri)
4. [Animasiya kataloqu — 12 pattern](#4-animasiya-kataloqu--12-pattern)
5. [Vizual dil: rəng, tipoqrafiya, forma](#5-vizual-dil-rəng-tipoqrafiya-forma)
6. [Səhifə strukturu və ritm](#6-səhifə-strukturu-və-ritm)
7. [IdeaNest-ə uyğunlaşdırma](#7-ideanest-ə-uyğunlaşdırma)
8. [Mobil (React Native) qarşılıqları](#8-mobil-react-native-qarşılıqları)
9. [Performans və əlçatanlıq qaydaları](#9-performans-və-əlçatanlıq-qaydaları)
10. [Tətbiq planı](#10-tətbiq-planı)

---

## 1. Analizin xülasəsi

### 1.1 Əsas tapıntı

Nixtio-nun dizaynı **effekt yığını deyil — məhdudiyyət sistemidir**.

Ən çox təəccübləndirən rəqəm: səhifədə **45 ədəd scroll animasiyası var və hamısı eyni tipdir** — `fade-up`. Fərqli olan yalnız gecikmədir (50/100/150/200/300/400ms). Nə `fade-left`, nə `zoom-in`, nə `flip`. Bir hərəkət, təkrar-təkrar.

Bu, təsadüf deyil, qərardır. Nəticəsi: sayt "animasiyalı" hiss olunmur — **sakit və bahalı** hiss olunur. Hərəkət diqqəti özünə çəkmir, məzmunu təqdim edir.

### 1.2 Rəqəmlərlə

| Metrik | Dəyər |
|---|---|
| Fərqli scroll animasiya tipi | **1** (`fade-up`) |
| Scroll animasiyası tətbiq sayı | 45 |
| Stagger gecikmə addımı | 50ms |
| Əsas transition müddəti | **300ms** |
| Fərqli easing funksiyası | 5 (əsasən `ease-in-out`) |
| Səhifə hündürlüyü | ~10,600px (~16 ekran) |
| Rəng dəyişəni (CSS var) | **7** |
| Şrift ailəsi | **1** (Inter) |
| Border radius dəyəri | 3 (18px / 25px / 30px) |

**7 rəng dəyişəni** və **1 şrift** — bu, çoxu agentliyin portfolio saytından daha az. Məhdudiyyət burada keyfiyyət yaradır.

### 1.3 Üç aparıcı prinsip

| Prinsip | Necə tətbiq olunur |
|---|---|
| **Bir hərəkət, çox təkrar** | `fade-up` hər yerdə. Yeni bölmə = yeni animasiya deyil. |
| **Hərəkət hiyerarxiyanı izah edir** | Stagger gecikməsi oxunuş sırasını göstərir: başlıq → təsvir → düymə → kartlar. |
| **Sürətli və qısa** | 300ms baza. Heç bir hərəkət 700ms-i keçmir. Uzun animasiya = yavaş sayt hissi. |

---

## 2. Hərəkət Kitabxanaları (faktiki stack)

JS bundle-larını skan edərək aşkarlanan kitabxanalar:

| Kitabxana | Rol | Harada görünür |
|---|---|---|
| **GSAP + ScrollTrigger** | Mürəkkəb scroll orkestrasiyası | Sətir-sətir mətn açılışı, sayğaclar, pinned bölmələr |
| **SplitText / SplitType** | Mətni sətir/söz/hərfə bölmək | `revealLinesOnScroll` komponenti |
| **AOS** (Animate On Scroll) | Sadə `fade-up` — 45 yerdə | `data-aos` atributları |
| **Lenis** | Yumşaq scroll (smooth scroll) | Bütün səhifə |
| **Splide** | Karusel / slayder | Testimonial və layihə slayderləri |
| **Lottie** | Vektor animasiya | İkonlar, mikro-illüstrasiyalar |
| **CSS `@keyframes`** | Marquee, modal, page transition | Native, JS-siz |

**Framework:** Next.js (Turbopack), SCSS Modules, Inter font, Vercel hosting.

### 2.1 Niyə iki kitabxana (GSAP + AOS)?

Bu, ağıllı bölgüdür:

- **AOS** — 45 sadə `fade-up` üçün. Ucuz, deklarativ, `data-aos` atributu ilə. JS yazmağa ehtiyac yoxdur.
- **GSAP** — yalnız timeline tələb edən 5-6 yerdə: sətir-sətir mətn, sayğac, scroll-a bağlı ardıcıllıq.

Hər şeyi GSAP-la etmək artıq işdir. Hər şeyi AOS-la etmək mümkün deyil. Bu bölgü doğrudur.

> **IdeaNest üçün tövsiyə:** Eyni bölgünü saxlayın, lakin AOS əvəzinə **Motion (Framer Motion)** istifadə edin — React ilə daha yaxşı inteqrasiya olunur və `useInView` hook-u AOS-un etdiyini komponent daxilində edir. GSAP-ı yalnız timeline lazım olanda əlavə edin.

---

## 3. Hərəkət Tokenləri

Saytdan çıxarılan faktiki dəyərlər:

```css
:root {
  /* Müddət */
  --transition-fast:  0.15s ease-in-out;   /* mikro: rəng, opacity */
  --transition-base:  0.3s  ease-in-out;   /* standart: hər şey */
  --transition-slow:  0.5s  ease-in-out;   /* böyük: layout, açılış */

  /* Easing */
  --ease-standard: cubic-bezier(0.4, 0, 0.2, 1);  /* Material standard */
  --ease-in-out:   ease-in-out;
  --ease-out:      ease-out;

  /* Stagger */
  --stagger-step:  50ms;   /* ardıcıl elementlər arasında */
}
```

### 3.1 Stagger nərdivanı

Faktiki gecikmə dəyərləri: **50 → 100 → 150 → 200 → 300 → 400ms**

İlk 4 addım 50ms-lik bərabər artım, sonra 100ms-ə keçir. Yəni:
- **Sıx qruplar** (kart şəbəkəsi, statistika) → 50ms addım
- **Ayrı bloklar** (başlıq → mətn → düymə) → 100ms addım

Bu incə fərq vacibdir: eyni qrupdakı elementlər "bir yerdə", fərqli bloklar "ardıcıl" hiss olunur.

### 3.2 Müddət qaydaları

| Nə | Müddət | Səbəb |
|---|---|---|
| Rəng, opacity (hover) | 150ms | Dərhal hiss olunmalıdır |
| Transform, standart hover | 300ms | Baza — hər şeyin default-u |
| Kart açılışı, akordeon | 400–600ms | Layout dəyişikliyi vaxt tələb edir |
| Səhifə keçidi | 300ms (transform) + 500ms (opacity) | İkisi fərqli sürətdə — dərinlik hissi verir |
| Marquee (sonsuz lent) | 30s linear | Fon hərəkəti, diqqət çəkməməlidir |

**Qadağa:** heç bir interaktiv element üçün >700ms. Yavaş animasiya = sındırılmış interfeys hissi.

---

## 4. Animasiya Kataloqu — 12 Pattern

Saytdan çıxarılmış hər pattern, faktiki CSS ilə.

### 4.1 Fade-up (əsas scroll animasiyası)

Səhifədəki **hər** scroll animasiyası budur.

```css
.fade-up {
  opacity: 0;
  transform: translateY(24px);
  transition: opacity 0.6s ease-out, transform 0.6s ease-out;
}
.fade-up.is-visible { opacity: 1; transform: translateY(0); }
```

React ilə (Motion):

```tsx
const fadeUp = {
  hidden:  { opacity: 0, y: 24 },
  visible: { opacity: 1, y: 0, transition: { duration: 0.6, ease: [0.4, 0, 0.2, 1] } },
};

<motion.div
  variants={fadeUp}
  initial="hidden"
  whileInView="visible"
  viewport={{ once: true, margin: '-10% 0px' }}   // AOS-un offset:10 qarşılığı
/>
```

**Kritik detal:** `viewport={{ once: true }}` — animasiya **bir dəfə** işləyir. Nixtio-da geri sürüşdürəndə element yenidən solmur. Bu, yuxarı-aşağı scroll edən istifadəçini yormamaq üçündür.

### 4.2 Stagger container

```tsx
const container = {
  visible: { transition: { staggerChildren: 0.05, delayChildren: 0.1 } },
};

<motion.ul variants={container} initial="hidden" whileInView="visible" viewport={{ once: true }}>
  {items.map(i => <motion.li key={i.id} variants={fadeUp} />)}
</motion.ul>
```

`staggerChildren: 0.05` = 50ms addım — saytdakı dəyər.

### 4.3 3D Flip düymə ⭐

Saytın **ən xarakterik** mikro-interaksiyası. Düymə mətni hover-də şaquli ox ətrafında fırlanır və altdan eyni mətnin dublikatı gəlir.

```css
.button__wrapper {
  perspective: 1000px;         /* 3D dərinlik */
  overflow: hidden;
  position: relative;
  display: inline-flex;
}

.button__text,
.button__duplicate {
  transform-style: preserve-3d;
  transition: transform 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

/* Dublikat başlanğıcda yuxarıda, arxaya çevrilmiş vəziyyətdə gizlidir */
.button__duplicate {
  position: absolute;
  inset: 0;
  transform: translateY(-100%) rotateX(90deg);
}

/* Hover: orijinal aşağı fırlanır, dublikat yerinə oturur */
.button:hover .button__text      { transform: translateY(52%) rotateX(-90deg); }
.button:hover .button__duplicate { transform: translateY(0)    rotateX(0deg);  }
```

```tsx
<button className={s.button}>
  <span className={s.wrapper}>
    <span className={s.text}>Layihəni dəstəklə</span>
    <span className={s.duplicate} aria-hidden="true">Layihəni dəstəklə</span>
  </span>
</button>
```

> `aria-hidden="true"` **məcburidir** — əks halda ekran oxuyucusu mətni iki dəfə oxuyur.

Eyni effekt sosial şəbəkə ikonlarında da var (`translateY(100%) rotateX(-90deg)`).

### 4.4 Səhifə keçidi (page transition) ⭐

Tam ekran örtük **aşağıdan yuxarı** sürüşərək səhifəni örtür, yeni səhifə yüklənir, örtük yuxarı çıxır.

```css
.overlay {
  position: fixed;
  inset: 0;
  height: 100dvh;
  z-index: 1000;
  background-color: var(--color-background);
  pointer-events: none;
  transition: transform 0.3s linear, opacity 0.5s linear;
}

.overlay.idle     { transform: translateY(100dvh); transition: none; }  /* ekrandan aşağıda gözləyir */
.overlay.exiting  { transform: translateY(0);      pointer-events: auto; }  /* yuxarı qalxıb örtür */
.overlay.entering { transform: translateY(-100dvh); }  /* yuxarıdan çıxır */

.pageWrapper {
  min-height: 100dvh;
  transition: transform 0.3s linear;
  will-change: transform;
}
```

**Diqqət:** `transform` 300ms, `opacity` 500ms — **fərqli sürətlər**. Bu uyğunsuzluq qəsdəndir: hərəkət tez bitir, solma davam edir, nəticədə keçid "yumşaq" hiss olunur.

`100dvh` (`vh` deyil) — mobil brauzerlərdə ünvan çubuğu problemini həll edir.

### 4.5 Sətir-sətir mətn açılışı (GSAP + SplitText) ⭐

Böyük başlıqlar sətir-sətir açılır. HTML-də mətn `<div>`-lərə bölünür, hər biri ayrıca animasiya olunur.

```tsx
// GSAP SplitText ilə
useGSAP(() => {
  const split = new SplitText(ref.current, { type: 'lines', linesClass: 'line' });
  gsap.from(split.lines, {
    yPercent: 100,
    opacity: 0,
    duration: 0.8,
    stagger: 0.08,
    ease: 'power3.out',
    scrollTrigger: { trigger: ref.current, start: 'top 80%', once: true },
  });
  return () => split.revert();   // təmizləmə MƏCBURİ
}, { scope: ref });
```

Hər sətir `overflow: hidden` konteynerin içində olmalıdır ki, aşağıdan "qalxma" effekti alınsın.

> **Diqqət:** SplitText DOM-u dəyişir. `split.revert()` çağırmasanız, React re-render zamanı DOM korlanır. Həmçinin şrift yüklənməsini gözləyin (`document.fonts.ready`) — əks halda sətirlər səhv bölünür.

### 4.6 Sonsuz marquee lenti

```css
@keyframes marquee {
  from { transform: translateX(0); }
  to   { transform: translateX(-50%); }   /* 50% — məzmun 2 dəfə təkrarlanır */
}

.track {
  display: flex;
  width: max-content;
  gap: 2.625rem;
  animation: marquee 30s linear infinite;
}

.track:hover { animation-play-state: paused; }   /* hover-də dayanır */
```

**Şərt:** məzmun DOM-da **iki dəfə** olmalıdır ki, `-50%`-də dövrə qırılmadan qapansın.

`animation-play-state: paused` hover-də — kiçik, lakin diqqətli detal: istifadəçi oxumaq istəyəndə lent dayanır.

### 4.7 Yığılan naviqasiya (shrinking nav pill) ⭐

Scroll edəndə naviqasiya şəffaf tam enli paneldən **ağ üzən həbə (pill)** çevrilir.

```css
.header {
  position: fixed;
  inset: 0 0 auto;
  background-color: transparent;
  transition: all 0.3s ease-in-out;
}

.nav {
  height: 2.5rem;
  padding: 0 2rem;
  border-radius: 1.875rem;
  transition: max-width 0.3s ease-in-out, width 0.3s ease-in-out;
}

/* Scroll edildikdən sonra */
.header.scrolled .content { padding: 1.25rem 1.625rem; }   /* 1.8rem-dən azalır */
.header.scrolled .nav {
  background-color: #fff;
  border: 1px solid rgba(10, 10, 10, 0.05);
  margin-inline: auto;                                      /* mərkəzə yığılır */
  max-width: 445px;                                         /* daralır */
}

/* Tünd fonlu səhifədə (hero üstündə) linklər ağ olur */
.header.black:not(.scrolled) .link { color: #fff; }
```

Üç şey eyni anda dəyişir: **en daralır**, **fon ağ olur**, **padding azalır**. Hamısı 300ms-də. Nəticə: səhifənin yuxarısında naviqasiya "yoxdur", scroll edəndə "peyda olur".

### 4.8 Sayğac animasiyası (count-up)

Səhifədə `0 +` və `0 %` gördüm — bunlar scroll-a çatanda hədəf rəqəmə qədər sayılır (600+, 100%).

```tsx
function CountUp({ to, suffix = '' }: { to: number; suffix?: string }) {
  const ref = useRef<HTMLSpanElement>(null);
  const inView = useInView(ref, { once: true, margin: '-20%' });

  useEffect(() => {
    if (!inView || !ref.current) return;
    const controls = animate(0, to, {
      duration: 2,
      ease: 'easeOut',
      onUpdate: (v) => { if (ref.current) ref.current.textContent = Math.round(v).toLocaleString('az-AZ') + suffix; },
    });
    return () => controls.stop();
  }, [inView, to, suffix]);

  return <span ref={ref} aria-label={`${to}${suffix}`}>0{suffix}</span>;
}
```

> `aria-label` ilə son dəyəri verin — ekran oxuyucusu dəyişən rəqəmi oxumamalıdır.

### 4.9 Akordeon

```css
.accordion__button {
  transform: rotate(0deg);
  transition: transform 0.4s ease-in;
}
.accordion__item.isOpen .accordion__button { transform: rotate(180deg); }

.accordion__content { transition: height 0.6s ease-in-out; }
.accordion__number  { transition: color 0.4s ease-in; }
```

İkon **180° fırlanır** (dəyişmir, fırlanır). Açılma 600ms — layout dəyişikliyi olduğu üçün daha uzun.

### 4.10 CTA ikon sürüşməsi

```css
.cta__icon { transition: transform 0.25s; }
.cta:hover .cta__icon { transform: translate(0.12rem, -0.12rem); }   /* sağ-yuxarı, ~2px */
```

Cəmi **2 piksel** hərəkət. Görünmür, amma hiss olunur. "Kənara çıxan ox" metaforasını gücləndirir.

### 4.11 Modal və banner girişi

```css
@keyframes slideIn {
  from { opacity: 0; transform: translateY(1.5rem); }
  to   { opacity: 1; transform: translateY(0); }
}
@keyframes slideOut {
  from { opacity: 1; transform: translateY(0); }
  to   { opacity: 0; transform: translateY(1.5rem); }
}
@keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }
```

Modal: backdrop `fadeIn`, məzmun `slideUp` (1rem) — ikisi eyni anda, fərqli məsafə. Backdrop yalnız solur, məzmun həm solur həm qalxır.

### 4.12 Tooltip avtomatik dövrə

```css
@keyframes tooltipFadeUp {
  0%   { opacity: 0; transform: translateY(4px); }
  20%  { opacity: 1; transform: translateY(0); }
  80%  { opacity: 1; transform: translateY(0); }
  100% { opacity: 0; transform: translateY(-4px); }
}
```

4 keyframe: gəlir (0–20%), qalır (20–80%), gedir (80–100%). Yuxarı çıxaraq yox olur — girdiyi yerə qayıtmır. Bu, "mesaj çatdı və getdi" hissi verir.

---

## 5. Vizual Dil: Rəng, Tipoqrafiya, Forma

> 🚫 **§5.1 (rəng) və §5.3 (radius) ləğv olunub** → [`UI-KIT.md`](./UI-KIT.md) §2, §3, §4.
> **§5.2 (tipoqrafiya) prinsipləri qüvvədədir** — `clamp()` fluid ölçülər və letter-spacing sıxılması. Faktiki dəyərlər `UI-KIT.md` §5-dədir.
> Aşağıdakı Nixtio analizi **tarixi arayış** kimi saxlanılıb.

### 5.1 Rəng sistemi — cəmi 7 dəyişən *(ləğv — tarixi arayış)*

```css
:root {
  --color-white:            #fff;
  --color-text:             #0a0a0a;              /* təmiz qara deyil — 4% yumşaldılmış */
  --color-text-secondary:   rgba(10,10,10,.6);    /* 60% — ikincili mətn */
  --color-text-tertiary:    rgba(255,255,255,.6); /* tünd fonda ikincili */
  --color-background:       #f5f5f5;              /* ağ deyil — isti boz */
  --color-background-secondary: #0a0a0a;          /* tünd bölmələr */
  --font-family: 'Inter';
}
```

Yeganə vurğu rəngi: **`rgb(95, 177, 96)`** — marquee lentində istifadə olunan yaşıl.

**Üç mühüm qərar:**

1. **Fon `#f5f5f5`, ağ deyil.** Kartlar ağ (`#fff`) olur və fondan **ayrılır** — kölgəyə ehtiyac qalmır. Bu, "elevation" problemini rəngsiz həll edir.
2. **Mətn `#0a0a0a`, `#000` deyil.** Təmiz qara ekranda sərt görünür və kontrast həddindən artıq olur.
3. **İkincili mətn opacity ilə, ayrı rənglə deyil.** `rgba(10,10,10,.6)` — bu, hansı fonda olursa olsun düzgün işləyir.

### 5.2 Tipoqrafiya

**Tək şrift: Inter.** Başqa heç nə.

Bütün başlıqlar `clamp()` ilə fluid:

```css
/* Hero başlıq */
font-size: clamp(2rem, 1.3838rem + 2.6291vw, 3.75rem);      /* 32px → 60px */

/* Bölmə başlığı */
font-size: clamp(2.1875rem, 1.8134rem + 1.5vw, 3rem);        /* 35px → 48px */

/* Addım nömrəsi */
font-size: clamp(2.625rem, 2.2289rem + 1.6901vw, 3.75rem);   /* 42px → 60px */

/* Gövdə mətni */
font-size: clamp(0.9375rem, 0.8715rem + 0.2817vw, 1.125rem); /* 15px → 18px */
```

**Letter-spacing sıxılması — kritik detal:**

| Ölçü | Letter-spacing |
|---|---|
| Böyük başlıq | `-0.06em` |
| Orta başlıq | `-0.05rem` |
| Kiçik başlıq | `-0.04em` |
| Düymə | `-0.0225rem` |

Şrift böyüdükcə hərflər **daha sıx** olur. Bu, böyük mətndə hərflər arası boşluğun optik olaraq şişməsini kompensasiya edir. Bu detalı buraxsanız, başlıqlar "ucuz" görünür — bu, saytın premium hissinin ən böyük səbəblərindən biridir.

### 5.3 Forma və radius

| Dəyər | İstifadə |
|---|---|
| `30px` | Nav pill, tam yuvarlaq düymələr |
| `25px` | Böyük kartlar, hero konteyner, video blok |
| `18px` | Kiçik kartlar |

Üç dəyər — daha çox deyil. Böyük səth = böyük radius məntiqinə əməl olunur.

**Kölgə (shadow) demək olar ki, yoxdur.** Ayırma üçün: fon rəngi fərqi + `1px solid rgba(10,10,10,0.05)` sərhəd. Bu, "flat, lakin təbəqəli" görünüş yaradır.

---

## 6. Səhifə Strukturu və Ritm

Səhifə **~10,600px** (16 ekran). Bu qədər uzun səhifə yalnız ritm düzgün olanda işləyir.

### 6.1 Bölmə ardıcıllığı

```
1.  Hero — tam ekran video fon, nəhəng logotip-tipoqrafiya, statistika
2.  Clients — logo şəbəkəsi
3.  Featured Projects — 2 sütunlu kart şəbəkəsi (rəngli fonlar)
4.  Why choose us — sayğaclarla statistika
5.  Services — nömrələnmiş akordeon (001, 002, ...)
6.  All-inclusive — yaşıl marquee lenti (tünd fon)
7.  How it works — nömrələnmiş addımlar
8.  Pricing — kartlar + marquee etiket
9.  Compare table — müqayisə cədvəli
10. Testimonials — slayder + xəritə
11. Team — hover effektli kartlar
12. FAQ — akordeon
13. Contact — forma + captcha
14. Footer — tünd, hündür (1229px)
```

### 6.2 Ritm qaydası: açıq → tünd → açıq

Fon rəngi növbələşir:

```
#f5f5f5 (açıq) → #0a0a0a (tünd) → #f5f5f5 (açıq) → #0a0a0a (footer)
```

Tünd bölmələr **nəfəsalma nöqtələridir**. 16 ekranlıq açıq səhifə yorucu olardı; tünd bölmələr onu fəsillərə bölür.

### 6.3 Hero konstruksiyası

```
<section>                              ← şəffaf, tam ekran
  └── <div class="container">          ← #0a0a0a fon, border-radius: 25px
        ├── video (object-fit: cover, absolute, autoplay muted loop)
        ├── poster (Next/Image, video yüklənənə qədər)
        └── content
              ├── logotip-başlıq (nəhəng, ağ)
              ├── services mətn
              ├── stats sətri
              └── description
```

**Hero tam enli deyil** — kənarlarda boşluq var və `25px` radius. Bu, "sayt bir kart kolleksiyasıdır" metaforasını lap başdan qurur.

---

## 7. IdeaNest-ə Uyğunlaşdırma

### 7.1 Birbaşa götürüləcəklər ✅

| Element | IdeaNest-də harada |
|---|---|
| `fade-up` + stagger sistemi | Discovery kart şəbəkəsi, layihə səhifəsi bölmələri |
| 3D flip düymə | **"Layihəni dəstəklə"** — əsas CTA |
| Yığılan nav pill | Qlobal header |
| Səhifə keçidi örtüyü | Route dəyişiklikləri |
| Sayğac animasiyası | Toplanmış məbləğ, backer sayı, faiz |
| Marquee | "Projects We Love" lenti, kateqoriya lenti |
| Akordeon | FAQ, mükafat pillələrinin təfərrüatı |
| `clamp()` fluid tipoqrafiya | Bütün başlıqlar |
| Letter-spacing sıxılması | Bütün başlıqlar |
| Açıq/tünd bölmə ritmi | Ana səhifə |
| 3 radius dəyəri | Bütün kartlar |

### 7.2 Dəyişdirilməli olanlar ⚠️

Nixtio **agentlik portfoliosudur** — 16 ekran scroll edən, yavaş, təsir bağışlayan sayt. IdeaNest **tranzaksiya platformasıdır**. Bu fərq bəzi qərarları dəyişir:

| Nixtio-da | IdeaNest-də | Səbəb |
|---|---|---|
| Lenis yumşaq scroll | **Yalnız marketinq səhifələrində** | Lenis native scroll-u ələ alır; uzun siyahılarda (discovery feed, backer report) ləngimə yaradır və klaviatura naviqasiyasını pozur |
| Səhifə keçidi hər yerdə | **Yalnız marketinq → app keçidində** | Pledge axınında 300ms örtük əlavə sürtünmədir. Ödəniş axını **dərhal** olmalıdır |
| Fade-up hər elementdə | **Yalnız ilk ekranda və bölmə başlıqlarında** | Discovery feed-də 50 kart varsa, hamısının fade-up etməsi scroll-u ləngidir |
| 2s sayğac animasiyası | **800ms** | Toplanmış məbləğ real məlumatdır — "yüklənir" hissi verməməlidir |
| Nəhəng hero video | **Kiçik hero + dərhal kontent** | Backer layihə axtarmağa gəlib, film izləməyə yox |
| Marquee 30s | Eyni saxla | |

### 7.3 Hər səhifə üçün hərəkət büdcəsi

| Səhifə | Animasiya səviyyəsi | Səbəb |
|---|---|---|
| Ana səhifə (marketinq) | **Tam** — hero, fade-up, sayğac, marquee, page transition | Təəssürat yaratmaq |
| Discovery / axtarış | **Minimal** — yalnız skeleton → content solması | Sürət hər şeydən vacibdir |
| Layihə səhifəsi | **Orta** — bölmə başlıqlarında fade-up, sayğac, sticky CTA | Hekayə + tranzaksiya balansı |
| **Pledge / ödəniş axını** | **Demək olar ki, sıfır** — yalnız addım keçidi (150ms) və yükləmə indikatoru | Hər animasiya burada şübhə yaradır |
| Creator dashboard | **Minimal** — qrafik çəkilmə animasiyası | İş aləti, şou deyil |
| Kampaniya redaktoru | **Sıfır** — yalnız auto-save indikatoru | İstifadəçi saatlarla burada işləyəcək |

> **Qayda:** İstifadəçi **pul verirsə** və ya **iş görürsə** — animasiya azalır. İstifadəçi **kəşf edirsə** — animasiya artır.

### 7.4 Rəng sistemi *(ləğv → [`UI-KIT.md`](./UI-KIT.md) §2, §10)*

```css
:root {
  /* Nixtio-dan götürülən neytral baza */
  --color-text:           #0a0a0a;
  --color-text-secondary: rgba(10, 10, 10, 0.6);
  --color-background:     #f5f5f5;
  --color-surface:        #ffffff;
  --color-surface-dark:   #0a0a0a;
  --color-border:         rgba(10, 10, 10, 0.05);

  /* IdeaNest-ə əlavə — funksional rənglər */
  --color-brand:          #5fb160;   /* Nixtio yaşılı — proqres barları, uğur */
  --color-success:        #16a34a;   /* hədəfə çatdı */
  --color-warning:        #f59e0b;   /* son 48 saat */
  --color-danger:         #dc2626;   /* ödəniş uğursuz */
  --color-info:           #2563eb;   /* məlumat */

  /* Radius */
  --radius-sm:  18px;
  --radius-md:  25px;
  --radius-pill: 30px;

  /* Hərəkət */
  --transition-fast: 0.15s ease-in-out;
  --transition-base: 0.3s  ease-in-out;
  --transition-slow: 0.5s  ease-in-out;
  --ease-standard:   cubic-bezier(0.4, 0, 0.2, 1);
  --stagger-step:    50ms;
}
```

Nixtio-nun 7 rəngi kifayət etmir — crowdfunding platformasında **status rəngləri** lazımdır: uğurlu/uğursuz layihə, uğursuz ödəniş, son tarix xəbərdarlığı. Bunlar neytral palitraya əlavə olunur, onu əvəz etmir.

### 7.5 IdeaNest-ə xas yeni animasiyalar

Nixtio-da olmayan, lakin bizə lazım olanlar:

| Animasiya | Təsvir | Müddət |
|---|---|---|
| **Proqres bar doldurma** | 0%-dən faktiki faizə. 100%-i keçəndə fərqli rəng + incə parıltı | 800ms `ease-out` |
| **Canlı vəd sayğacı** | Yeni vəd gələndə rəqəm "çevrilir" (odometer), yaşıl işartı | 400ms |
| **Geri sayım** | Saniyə dəyişimi — animasiya **YOX**, sadəcə rəqəm dəyişir | 0ms |
| **Hədəfə çatdı** | Konfeti + proqres barın rəng dəyişməsi (bir dəfə) | 1.5s |
| **Vəd təsdiqi** | Checkmark çəkilmə (SVG path) + haptic (mobil) | 600ms |
| **Stok azalması** | "5 left" rəqəmi dəyişəndə qırmızı pulse | 300ms |
| **Skeleton → content** | Kart yüklənməsi | 200ms crossfade |

> **Geri sayım animasiyası olmamalıdır.** Hər saniyə animasiya işləsə, səhifə heç vaxt "sakitləşmir" və batareya yeyir. Rəqəm sadəcə dəyişsin.

---

## 8. Mobil (React Native) Qarşılıqları

Web-dəki hər pattern-in mobil qarşılığı:

| Web | React Native |
|---|---|
| `fade-up` (Motion) | `react-native-reanimated` — `FadeInDown.duration(600).delay(i*50)` |
| Stagger | `entering={FadeInDown.delay(index * 50)}` FlashList item-də |
| 3D flip düymə | **Sadələşdir** — `scale: 0.97` press animasiyası + `expo-haptics` |
| Səhifə keçidi | `expo-router` native stack keçidləri (iOS: slide, Android: fade) |
| Sayğac | `useSharedValue` + `withTiming` + `ReText` |
| Marquee | `Animated.loop` + `withRepeat(withTiming(-width, {duration: 30000, easing: Easing.linear}))` |
| Akordeon | `LayoutAnimation` və ya Reanimated `Layout` |
| Lenis smooth scroll | **Yoxdur** — native scroll onsuz da yumşaqdır, toxunma |
| Hover | **Yoxdur** — `Pressable` `pressed` state ilə əvəzlə |

### 8.1 Mobil-spesifik qaydalar

```tsx
// Bütün siyahı elementləri üçün standart giriş
import Animated, { FadeInDown } from 'react-native-reanimated';

<Animated.View entering={FadeInDown.duration(400).delay(Math.min(index * 50, 300))}>
  <ProjectCard {...item} />
</Animated.View>
```

`Math.min(index * 50, 300)` — **gecikmə tavanı**. 50-ci element 2.5 saniyə gözləməməlidir.

**Haptic feedback nöqtələri:**

| Hadisə | Haptic |
|---|---|
| Layihə saxlama (❤️) | `impactAsync(Light)` |
| Mükafat seçimi | `selectionAsync()` |
| **Vəd təsdiqi** | `notificationAsync(Success)` |
| Ödəniş uğursuz | `notificationAsync(Error)` |
| Pull-to-refresh | `impactAsync(Medium)` |

**Animasiya müddətləri mobildə 20% qısa olsun** — kiçik ekranda hərəkət məsafəsi azdır, eyni müddət yavaş hiss olunur. Web 600ms → mobil 400ms.

---

## 9. Performans və Əlçatanlıq Qaydaları

### 9.1 Performans

| Qayda | Səbəb |
|---|---|
| **Yalnız `transform` və `opacity` animasiya et** | Bunlar GPU-da işləyir. `width`, `height`, `top`, `margin` hər kadrda layout hesablatdırır |
| `will-change: transform` — **yalnız animasiya zamanı** | Daimi qoyulsa, GPU yaddaşı boşuna tutulur. Animasiya bitəndə sil |
| `viewport={{ once: true }}` | Təkrar animasiya = təkrar hesablama |
| Uzun siyahılarda animasiya **yoxdur** | Discovery feed-də 50 kart × fade-up = scroll jank |
| Hero video: `poster` + `preload="none"` | LCP-ni video bloklamamalıdır |
| GSAP-ı **lazy import** et | Yalnız lazım olan səhifədə (`next/dynamic`) |
| Lenis-i marketinq route-larında **şərti yüklə** | App route-larında yükləmə |
| `content-visibility: auto` uzun səhifələrdə | Görünməyən bölmələr render olunmur |

**Ölçmə hədəfləri:**

| Metrik | Hədəf |
|---|---|
| LCP | < 2.0s |
| CLS | < 0.05 (animasiya CLS yaratmamalıdır!) |
| INP | < 200ms |
| Animasiya FPS | 60fps (Chrome DevTools Performance) |

> **CLS xəbərdarlığı:** `fade-up` elementi `translateY(24px)`-dən gəlirsə, layout dəyişmir (transform layout-a təsir etmir) — bu təhlükəsizdir. Lakin `height: 0 → auto` animasiyası CLS yaradır. Akordeonda `grid-template-rows: 0fr → 1fr` istifadə edin.

### 9.2 Əlçatanlıq (Accessibility)

**`prefers-reduced-motion` — məcburi, opsional deyil:**

```css
@media (prefers-reduced-motion: reduce) {
  *,
  *::before,
  *::after {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.01ms !important;
    scroll-behavior: auto !important;
  }
}
```

React ilə:

```tsx
const shouldReduceMotion = useReducedMotion();

<motion.div
  variants={shouldReduceMotion ? undefined : fadeUp}
  initial={shouldReduceMotion ? false : 'hidden'}
  whileInView="visible"
/>
```

**Digər tələblər:**

| Tələb | Detal |
|---|---|
| 3D flip düymədə dublikat mətn | `aria-hidden="true"` — əks halda iki dəfə oxunur |
| Sayğac | `aria-label` ilə son dəyər; dəyişən rəqəm `aria-live` **olmamalıdır** |
| Marquee | `aria-hidden="true"` əgər dekorativdirsə; deyilsə, statik mətn alternativi ver |
| Səhifə keçidi | Örtük `aria-hidden="true"`, fokus yeni səhifənin `<h1>`-inə köçürülməlidir |
| Akordeon | `aria-expanded`, `aria-controls`, `<button>` (div deyil) |
| Fokus göstəricisi | Animasiya edilmiş elementlərdə də görünməlidir — `:focus-visible` outline silinməməlidir |
| Kontrast | `rgba(10,10,10,.6)` `#f5f5f5` üzərində = **4.6:1** ✅ (AA keçir). Daha açıq etməyin |
| Video | `autoplay` yalnız `muted` ilə; nəzarət düymələri əlçatan olmalıdır |

### 9.3 Sınaq siyahısı

- [ ] `prefers-reduced-motion: reduce` ilə saytı gəz — heç nə hərəkət etməməlidir
- [ ] Klaviatura ilə tam naviqasiya (Tab, Enter, Escape)
- [ ] Ekran oxuyucusu ilə düymə və sayğacları yoxla
- [ ] CPU 4x throttling ilə scroll — jank varmı?
- [ ] Chrome DevTools → Rendering → "Paint flashing" — animasiya zamanı repaint olmamalıdır
- [ ] Lighthouse: CLS < 0.05
- [ ] Yavaş 3G-də hero video LCP-ni bloklamır

---

## 10. Tətbiq Planı

### 10.1 Texnologiya seçimi

| Ehtiyac | Kitabxana | Qeyd |
|---|---|---|
| Əsas animasiya | **`motion`** (Framer Motion 11+) | React-native, `useInView`, `useReducedMotion` daxilində |
| Mürəkkəb scroll timeline | **`gsap`** + `@gsap/react` | Yalnız lazım olan səhifədə lazy import |
| Mətn bölmə | **`gsap/SplitText`** və ya `split-type` | Şrift yüklənməsini gözlə |
| Yumşaq scroll | **`lenis`** | **Yalnız marketinq route-ları** |
| Karusel | **`embla-carousel-react`** | Splide-dan daha yüngül və React-native |
| Mobil animasiya | **`react-native-reanimated`** 3 | |
| Mobil haptic | **`expo-haptics`** | |

```jsonc
// apps/web/package.json — əlavələr
{
  "motion": "^11.15.0",
  "@gsap/react": "^2.1.1",
  "gsap": "^3.12.5",
  "lenis": "^1.1.18",
  "embla-carousel-react": "^8.5.1",
  "embla-carousel-autoplay": "^8.5.1"
}
```

### 10.2 Fayl strukturu

```
packages/design-tokens/
├── motion.ts          # müddət, easing, stagger dəyərləri
├── colors.ts
├── typography.ts      # clamp() ölçüləri + letter-spacing
└── radius.ts

apps/web/src/components/motion/
├── FadeUp.tsx         # əsas scroll animasiya wrapper-i
├── StaggerGroup.tsx   # stagger container
├── FlipButton.tsx     # 3D flip CTA
├── CountUp.tsx        # sayğac
├── Marquee.tsx        # sonsuz lent
├── PageTransition.tsx # route keçid örtüyü
├── RevealLines.tsx    # GSAP sətir-sətir mətn
└── ProgressBar.tsx    # IdeaNest-ə xas: vəd proqresi

apps/mobile/src/components/motion/
├── FadeInItem.tsx
├── AnimatedCounter.tsx
└── PressableScale.tsx
```

### 10.3 Ardıcıllıq

| Addım | İş |
|---|---|
| 1 | `packages/design-tokens` — rəng, tipoqrafiya, hərəkət tokenləri |
| 2 | `FadeUp` + `StaggerGroup` — bunlar UI-ın 80%-ini örtür |
| 3 | `FlipButton` — əsas CTA-lar üçün |
| 4 | `CountUp` + `ProgressBar` — layihə statistikası |
| 5 | Header (yığılan nav pill) |
| 6 | `PageTransition` — yalnız marketinq route-larında |
| 7 | `Marquee` + `RevealLines` — ana səhifə |
| 8 | Mobil qarşılıqlar |
| 9 | `prefers-reduced-motion` auditi + Lighthouse |

### 10.4 Bir cümlədə

> **Nixtio-nun sirri effektlərin sayında deyil, onların azlığındadır.** Bir animasiya tipi, bir şrift, yeddi rəng, üç radius, üç müddət. IdeaNest üçün eyni intizamı saxlayın — lakin pul axınına yaxınlaşdıqca hərəkəti azaldın.

---

## Əlavə — Analiz metodologiyası

Bu sənəd təxminə əsaslanmır. Aşağıdakılar brauzerdə (Playwright) faktiki olaraq oxunub:

| Nə | Necə |
|---|---|
| Animasiya tipləri və gecikmələr | Bütün `[data-aos]` elementlərinin atributları sayılıb (45 element, 1 tip) |
| CSS dəyişənləri | `getComputedStyle(document.documentElement)` |
| Transition-lar və easing-lər | CSSOM-da bütün `styleSheets` rekursiv gəzilib |
| Keyframe-lər | `CSSRule.KEYFRAMES_RULE` filtri ilə çıxarılıb |
| Hover transformları | `:hover` selektorları + `transform` dəyərləri |
| Kitabxanalar | 14 JS chunk `fetch` edilib, imza axtarışı (`gsap`, `ScrollTrigger`, `lenis`, `splide`, `lottie`, `SplitText`) |
| Tipoqrafiya | `clamp()` ehtiva edən bütün `font-size` qaydaları |
| Struktur | DOM ölçüləri, fon rəngləri, radius dəyərləri + skrinşotlar |
