# IdeaNest UI Kit — Dark + Lime Sistemi

**Referans:** İstifadəçi tərəfindən təqdim edilmiş CRM/Workspace dashboard dizaynı (14 Avqust 2026).

**Status:** Bu sənəd platformanın **rəng, səth və komponent** sistemidir. Hərəkət (animasiya) sistemi ayrıca `DESIGN-SYSTEM.md`-dədir.

---

## Mündəricat

1. [Sistemin xülasəsi](#1-sistemin-xülasəsi)
2. [Rəng palitrası](#2-rəng-palitrası)
3. [Səth və elevation sistemi](#3-səth-və-elevation-sistemi)
4. [Radius sistemi](#4-radius-sistemi)
5. [Tipoqrafiya](#5-tipoqrafiya)
6. [Boşluq və layout](#6-boşluq-və-layout)
7. [Komponent kataloqu](#7-komponent-kataloqu)
8. [IdeaNest ekranlarına tətbiq](#8-ideanest-ekranlarına-tətbiq)
9. [Əlçatanlıq qaydaları](#9-əlçatanlıq-qaydaları)
10. [Tailwind konfiqurasiyası](#10-tailwind-konfiqurasiyası)
11. [Nixtio sənədi ilə uzlaşdırma](#11-nixtio-sənədi-ilə-uzlaşdırma)

---

## 1. Sistemin Xülasəsi

### 1.1 Dizaynın üç qanunu

Şəkildəki dizaynı öyrənəndə üç struktur qərar görünür:

| # | Qanun | İzah |
|---|---|---|
| **1** | **Qara — fon, ağ — vurğu** | Ağ burada baza rəng deyil. Ağ panellər və həblər qara üzərində **üzür**. Bu, adi tünd temanın tərsidir. |
| **2** | **Lampa yaşılı yalnız "indi"-ni işarələyir** | Hər sıradakı **bir** kart limon-yaşıldır — aktiv, prioritet və ya cari element. Yaşıl bəzək deyil, **vəziyyət göstəricisidir**. |
| **3** | **Hər şey həb formasındadır** | Düymələr, filtrlər, teqlər, avatar qrupları — tam yuvarlaq. Kartlar 24–28px radius. Kvadrat künc yoxdur. |

### 1.2 Nə üçün bu sistem işləyir

Şəkildə eyni anda 40-dan çox interaktiv element var — normalda bu, xaos yaradardı. Xaos olmamasının səbəbi:

- **Rəng iyerarxiyanı daşıyır.** Qara = fon, tünd-boz = adi kart, ağ = fokus, yaşıl = təcili. İstifadəçi rəngə baxıb prioritet anlayır, mətn oxumadan.
- **Formanın özü qrup yaradır.** Həblər (filtr, teq) bir kateqoriya, kartlar başqa kateqoriya. Forma = funksiya.
- **Boşluq az, amma sabitdir.** Kartlar sıx yerləşib, lakin aralarındakı boşluq həmişə eynidir.

### 1.3 Bu, crowdfunding üçün nə deməkdir

Şəkildəki dizayn **CRM dashboard**-dur — məlumat sıxlığı yüksək, iş aləti. IdeaNest-in isə iki fərqli üzü var:

| Üz | Xarakter | Bu sistem uyğundurmu |
|---|---|---|
| **Dashboard, creator alətləri, admin** | Məlumat sıx, iş aləti | ✅ **Tam uyğun** — birbaşa tətbiq et |
| **Discovery, layihə səhifəsi** | Şəkil ağırlıqlı, kəşf | ✅ Uyğun — qara fon şəkilləri gücləndirir |
| **Kampaniya hekayəsi (uzun mətn)** | 2000+ söz oxunuş | ⚠️ **Diqqət tələb edir** — aşağıda |

> **Qeyd etməli olduğum bir məsələ:** Uzun mətnin (kampaniya hekayəsi) tam qara fonda ağ ilə oxunması yorucudur — "halation" effekti yaranır, xüsusən astiqmatizmi olan istifadəçilərdə. Bu, dizaynın səhvi deyil, uzun mətnin xüsusiyyətidir.
>
> **Həll (sistemi pozmadan):** Hekayə mətn bloku üçün `--surface-2` (#1A1A1A) səth, `--text-primary` əvəzinə `rgba(255,255,255,0.92)` və `line-height: 1.75` istifadə edin. Bu, hələ də tünd sistemdir, sadəcə tam qara deyil. Sistem eyni qalır, oxunuş rahatlaşır. Bölmə 8.4-də detallıdır.

---

## 2. Rəng Palitrası

### 2.1 Baza — Neytral (qara şkalası)

| Token | Hex | İstifadə |
|---|---|---|
| `--black` | `#000000` | Səhifə fonu — ən dərin qat |
| `--surface-1` | `#0D0D0D` | Əsas fon (OLED-də tam qara istəmirsinizsə) |
| `--surface-2` | `#161616` | Adi kart, panel |
| `--surface-3` | `#1F1F1F` | Kart üzərində kart, input sahəsi |
| `--surface-4` | `#2A2A2A` | Hover vəziyyəti, seçilmiş element |
| `--border` | `rgba(255,255,255,0.08)` | Kart sərhədi — çox incə |
| `--border-strong` | `rgba(255,255,255,0.16)` | Fokus, aktiv sərhəd |
| `--divider` | `rgba(255,255,255,0.06)` | Ayırıcı xətlər |

### 2.2 Mətn

| Token | Dəyər | İstifadə | Kontrast (#0D0D0D üzərində) |
|---|---|---|---|
| `--text-primary` | `#FFFFFF` | Başlıq, ad, əsas rəqəm | **20.4:1** ✅ |
| `--text-secondary` | `rgba(255,255,255,0.64)` | Alt başlıq, təsvir, rol | **9.2:1** ✅ |
| `--text-tertiary` | `rgba(255,255,255,0.40)` | Meta, tarix, placeholder | **4.9:1** ✅ AA |
| `--text-disabled` | `rgba(255,255,255,0.24)` | Deaktiv | 2.6:1 — yalnız qeyri-mətn |
| `--text-on-lime` | `#0A0A0A` | Yaşıl səth üzərində mətn | **15.8:1** ✅ |
| `--text-on-white` | `#0A0A0A` | Ağ səth üzərində mətn | **19.3:1** ✅ |

### 2.3 Vurğu — Lime (əsas brend rəngi)

Şəkildəki xarakterik limon-yaşıl. Bu, sistemin **imzasıdır**.

| Token | Hex | İstifadə |
|---|---|---|
| `--lime-300` | `#DCFB7A` | Açıq variant, yaşıl üzərində vurğu |
| `--lime-400` | `#D2F95C` | Hover |
| **`--lime-500`** | **`#C6F432`** | **Əsas** — aktiv kart, CTA, proqres |
| `--lime-600` | `#B0DE1E` | Basılmış (pressed) vəziyyət |
| `--lime-700` | `#94BC15` | Sərhəd, ikon |
| `--lime-glow` | `rgba(198,244,50,0.24)` | Kənar parıltı, fokus halqası |

**Qızıl qayda:**

> Lime **həmişə səthdir**, mətn deyil. Yaşıl fon + qara mətn. **Heç vaxt** ağ fonda yaşıl mətn — kontrast 1.3:1, oxunmur.

Yaşıl mətn yalnız qara fonda icazəlidir (kontrast 16:1) — məsələn kiçik status etiketi.

### 2.4 Status rəngləri

Şəkildəki kiçik nöqtələr (interest level) və qırmızı düymədən götürülüb, IdeaNest üçün genişləndirilib:

| Token | Hex | İstifadə |
|---|---|---|
| `--success` | `#34D058` | Hədəfə çatdı, ödəniş uğurlu, çatdırıldı |
| `--warning` | `#FFB020` | Son 48 saat, survey gecikir |
| `--danger` | `#FF4438` | Ödəniş uğursuz, layihə dayandırıldı, zəngi bitir |
| `--info` | `#4A9EFF` | Məlumat, moderasiyada |
| `--hot` | `#FF6B35` | "Hot" / trend layihə (şəkildəki alov ikonu) |

**Nöqtə göstəricisi (dot indicator)** — şəkildəki 5 rəngli nöqtə sırası. IdeaNest-də **maliyyələşdirmə səviyyəsi** üçün:

```
○○○○○  0–25%      --text-disabled
●○○○○  25–50%     --warning
●●○○○  50–75%     --lime-500
●●●○○  75–100%    --lime-500
●●●●●  100%+      --success
```

### 2.5 Ağ — vurğu rəngi kimi

Bu sistemdə ən vacib və ən çox səhv başa düşülən nöqtə:

| Token | Dəyər | İstifadə |
|---|---|---|
| `--white-surface` | `#FFFFFF` | Üzən panel, əsas həb düymə, modal |
| `--white-muted` | `#F4F4F4` | Ağ panel daxilində alt səth |

Şəkildə ağ istifadə olunan yerlər: naviqasiya həbi, "New Task" düyməsi, video zəng paneli, "Summary" paneli, filtr həbləri.

**Nə vaxt ağ:** element diqqət tələb edirsə, sistemin "üstündə" durursa (modal, floating panel), və ya əsas hərəkətdirsə.
**Nə vaxt yaşıl:** element **cari/aktiv/təcili** vəziyyətdədirsə.

İkisi eyni kartda olmamalıdır.

---

## 3. Səth və Elevation Sistemi

Bu sistemdə **kölgə yoxdur**. Dərinlik rəng fərqi ilə yaradılır.

```
Qat 0 — Səhifə fonu        #000000 / #0D0D0D
   │
   ├─ Qat 1 — Adi kart      #161616  + border rgba(255,255,255,.08)
   │     │
   │     └─ Qat 2 — İç blok #1F1F1F  (input, teq konteyner)
   │
   ├─ Qat 3 — Aktiv kart    #C6F432  (lime — vəziyyət, elevation deyil)
   │
   └─ Qat 4 — Üzən panel    #FFFFFF  + shadow 0 24px 64px rgba(0,0,0,.6)
```

**Yeganə kölgə** ağ üzən panellərdədir — çünki onlar həqiqətən "yuxarıda"dır:

```css
--shadow-float: 0 24px 64px -12px rgba(0, 0, 0, 0.7);
--shadow-panel: 0 8px 32px -8px rgba(0, 0, 0, 0.5);
```

Tünd kartlarda kölgə **istifadə etməyin** — qara üzərində qara kölgə görünmür, yalnız render xərci yaradır.

---

## 4. Radius Sistemi

Şəkildən ölçülən dəyərlər:

| Token | Dəyər | İstifadə |
|---|---|---|
| `--radius-full` | `9999px` | Həblər, filtr çipləri, avatar, dairəvi ikon düymələr |
| `--radius-xl` | `28px` | Böyük kartlar, panellər, modal |
| `--radius-lg` | `20px` | Orta kart, layihə kartı |
| `--radius-md` | `14px` | Kart daxilindəki bloklar, input |
| `--radius-sm` | `10px` | Teq, kiçik etiket, şəkil thumbnail |

**Qayda:** Böyük səth = böyük radius. İç element **həmişə** valideynindən kiçik radiusa malikdir (`radius_child ≈ radius_parent - padding`).

---

## 5. Tipoqrafiya

### 5.1 Şrift seçimi

Şəkildəki şrift həndəsi-qrotesk xarakterlidir (yumşaq `a`, `e`, geniş `W`). Üç uyğun seçim:

| Şrift | Xarakter | Tövsiyə |
|---|---|---|
| **General Sans** | Həndəsi, müasir, dəqiq — şəkilə ən yaxın | ⭐ **Birinci seçim** |
| **Sora** | Bir az daha texniki, gözəl rəqəmlər | İkinci |
| **Inter** | Neytral, ən geniş dil dəstəyi | Təhlükəsiz seçim |

> **Azərbaycan dili üçün kritik yoxlama:** Şrift `ə ğ ı ö ş ü ç İ Ə Ğ` simvollarını tam dəstəkləməlidir. **General Sans və Sora bunları dəstəkləyir**, lakin `ə` glifinin keyfiyyətini mütləq gözlə yoxlayın — bəzi şriftlərdə bu hərf sonradan əlavə edilib və digərləri ilə uyğunlaşmır. Şübhə varsa **Inter** seçin: `ə` orijinal dizaynın hissəsidir.

**Qərar:** Başlıqlar üçün **General Sans**, gövdə mətni və rəqəmlər üçün **Inter**. İki şrift maksimum.

### 5.2 Ölçü şkalası

```css
/* Display — böyük statistika rəqəmləri (şəkildəki "34", "20", "3") */
--text-display:  clamp(2.5rem, 2rem + 2.2vw, 4rem);      /* 40 → 64px */
--text-h1:       clamp(2rem, 1.6rem + 1.8vw, 3rem);      /* 32 → 48px */
--text-h2:       clamp(1.5rem, 1.3rem + 0.9vw, 2rem);    /* 24 → 32px */
--text-h3:       clamp(1.25rem, 1.15rem + 0.5vw, 1.5rem);/* 20 → 24px */
--text-lg:       1.125rem;   /* 18px — kart adı */
--text-base:     1rem;       /* 16px — gövdə */
--text-sm:       0.875rem;   /* 14px — alt başlıq, rol */
--text-xs:       0.75rem;    /* 12px — teq, meta, sayğac */
--text-2xs:      0.6875rem;  /* 11px — badge */
```

### 5.3 Çəki və letter-spacing

| Rol | Çəki | Letter-spacing |
|---|---|---|
| Display rəqəm | 600 | `-0.04em` |
| H1 | 600 | `-0.035em` |
| H2 / H3 | 600 | `-0.03em` |
| Kart adı (18px) | 500 | `-0.02em` |
| Gövdə | 400 | `-0.01em` |
| Teq / badge | 500 | `0` |
| Düymə | 500 | `-0.01em` |

**Nixtio-dan gələn qayda burada da qalır:** şrift böyüdükcə hərflər sıxlaşır. Bu, böyük mətnin "ucuz" görünməsinin qarşısını alır.

### 5.4 Line-height

| Kontekst | Dəyər |
|---|---|
| Display / H1 | `1.05` |
| H2 / H3 | `1.2` |
| Kart adı | `1.3` |
| Gövdə (qısa) | `1.5` |
| **Uzun mətn (kampaniya hekayəsi)** | **`1.75`** ← tünd fonda oxunuş üçün kritik |

---

## 6. Boşluq və Layout

### 6.1 Boşluq şkalası (4px baza)

```
4 · 8 · 12 · 16 · 20 · 24 · 32 · 40 · 48 · 64 · 80 · 96
```

### 6.2 Şəkildən ölçülən dəyərlər

| Element | Dəyər |
|---|---|
| Kart daxili padding | `20px` (kiçik) / `24px` (böyük) |
| Kartlar arası boşluq (grid) | `16px` |
| Bölmələr arası | `32px` |
| Həb padding | `10px 18px` |
| Dairəvi ikon düymə | `40px × 40px` (kiçik `32px`) |
| Avatar | `40px` (kartda) / `28px` (qrupda) / `56px` (profil) |
| Sol ikon rail eni | `72px` |
| Üst bar hündürlüyü | `64px` |

### 6.3 Dashboard layout skeleti

```
┌──┬────────────────────────────────────────────────────┐
│  │  [Cədvəl həbi]      [Statistika sırası]            │  ← Üst bar
│ı │────────────────────────────────────────────────────│
│k │  BAŞLIQ                    [+ Yeni]                │
│o │                                                    │
│n │  Bölmə adı  (say)   [🔍] [⇅]  [Filtr həbləri...]  │
│  │  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐      │
│r │  │  kart  │ │  kart  │ │ AKTİV  │ │  kart  │  →   │  ← üfüqi scroll
│a │  └────────┘ └────────┘ └ lime ──┘ └────────┘      │
│i │                                                    │
│l │  Bölmə adı  (say)   [🔍] [⇅]  [Filtr həbləri...]  │
│  │  ┌────────┐ ┌────────┐ ┌────────┐                 │
│  │  │ AKTİV  │ │  kart  │ │  kart  │             →   │
│  │  └ lime ──┘ └────────┘ └────────┘                 │
└──┴────────────────────────────────────────────────────┘
```

**Əsas struktur ideyası:** üfüqi sürüşən kart sıraları (rail), hər sıranın öz filtri və sayğacı var. Bu, çoxlu məlumatı şaquli scroll-suz göstərir.

---

## 7. Komponent Kataloqu

Şəkildən çıxarılan hər komponent, spesifikasiya ilə.

### 7.1 Kart (əsas)

Üç variant — **eyni ölçü, fərqli vəziyyət**:

```tsx
type CardVariant = 'default' | 'active' | 'floating';
```

| Variant | Fon | Mətn | Sərhəd | Nə vaxt |
|---|---|---|---|---|
| `default` | `--surface-2` | `--text-primary` | `--border` | Adi element |
| `active` | `--lime-500` | `--text-on-lime` | yoxdur | Cari / prioritet / təcili |
| `floating` | `--white-surface` | `--text-on-white` | yoxdur + `--shadow-float` | Modal, üzən panel |

```css
.card {
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 20px;
  transition: background-color 0.3s ease-in-out, transform 0.3s ease-in-out;
}
.card:hover     { background: var(--surface-3); }
.card--active   { background: var(--lime-500); border-color: transparent; color: var(--text-on-lime); }
.card--floating { background: #fff; color: var(--text-on-white); box-shadow: var(--shadow-float); }
```

**Kart anatomiyası** (şəkildəki "Jane Doe" kartı):

```
┌─────────────────────────────────┐
│ [avatar]                    [↗] │  ← genişləndirmə düyməsi (sağ üst)
│                                 │
│ Ad Soyad                        │  ← 18px / 500
│ Rol / təşkilat                  │  ← 14px / secondary
│                                 │
│ Source                    ●●●●● │  ← etiket + nöqtə göstəricisi
│ [teq] [teq]                     │  ← 12px həblər
└─────────────────────────────────┘
```

### 7.2 Həb düymə (Pill Button)

```css
.pill {
  display: inline-flex; align-items: center; gap: 8px;
  height: 40px; padding: 0 18px;
  border-radius: var(--radius-full);
  font-size: 14px; font-weight: 500; letter-spacing: -0.01em;
  transition: background-color 0.15s ease-in-out, transform 0.15s ease-in-out;
}
.pill--primary   { background: #fff;              color: var(--text-on-white); }
.pill--accent    { background: var(--lime-500);   color: var(--text-on-lime); }
.pill--ghost     { background: var(--surface-3);  color: var(--text-primary); }
.pill--outline   { background: transparent; border: 1px solid var(--border-strong); color: var(--text-primary); }

.pill:hover  { transform: translateY(-1px); }
.pill:active { transform: translateY(0) scale(0.98); }
.pill--primary:hover { background: #f0f0f0; }
.pill--accent:hover  { background: var(--lime-400); }
```

### 7.3 Filtr çipi (Filter Chip)

Şəkildəki "All / Hot Client / Great interest / Medium interest" sırası.

```css
.chip {
  height: 34px; padding: 0 16px;
  border-radius: var(--radius-full);
  background: var(--surface-2);
  border: 1px solid var(--border);
  color: var(--text-secondary);
  font-size: 13px; font-weight: 500;
  white-space: nowrap;
  transition: all 0.15s ease-in-out;
}
.chip:hover        { background: var(--surface-3); color: var(--text-primary); }
.chip[data-active] { background: #fff; border-color: transparent; color: var(--text-on-white); }
```

Konteyner üfüqi sürüşən olmalıdır:

```css
.chip-row {
  display: flex; gap: 8px;
  overflow-x: auto;
  scrollbar-width: none;
  scroll-snap-type: x proximity;
  mask-image: linear-gradient(90deg, #000 90%, transparent);  /* sağ kənar solur */
}
```

### 7.4 Dairəvi ikon düymə

```css
.icon-btn {
  width: 40px; height: 40px;
  display: grid; place-items: center;
  border-radius: var(--radius-full);
  background: var(--surface-3);
  color: var(--text-primary);
  transition: background-color 0.15s, transform 0.15s;
}
.icon-btn:hover  { background: var(--surface-4); transform: scale(1.06); }
.icon-btn--light { background: #fff; color: var(--text-on-white); }
.icon-btn--accent{ background: var(--lime-500); color: var(--text-on-lime); }
.icon-btn--danger{ background: var(--danger); color: #fff; }
.icon-btn--sm    { width: 32px; height: 32px; }
```

**Genişləndirmə düyməsi (↗)** — şəkildə demək olar ki, hər kartın sağ üstündə var. IdeaNest-də: "layihəyə keç", "detallara bax".

```css
.expand-btn {
  position: absolute; top: 16px; right: 16px;
  width: 32px; height: 32px;
  background: var(--surface-4);
  opacity: 0;                      /* yalnız hover-də görünür */
  transition: opacity 0.2s, transform 0.2s;
}
.card:hover .expand-btn { opacity: 1; }
.expand-btn:hover       { transform: translate(2px, -2px); }   /* Nixtio-dan: 2px ox sürüşməsi */
```

### 7.5 Teq (Tag)

Şəkildəki "Linkedin", "Email", "Typeform" kimi kiçik etiketlər.

```css
.tag {
  height: 26px; padding: 0 10px;
  border-radius: var(--radius-sm);
  background: var(--surface-3);
  color: var(--text-secondary);
  font-size: 12px; font-weight: 500;
}
.tag--on-lime { background: rgba(10,10,10,0.10); color: rgba(10,10,10,0.72); }
```

> Yaşıl kartın içindəki teqlər `--surface-3` ola bilməz — görünməz olur. Yaşıl üzərində **qara şəffaflıq** istifadə edin.

### 7.6 Avatar və avatar qrupu

```css
.avatar {
  border-radius: var(--radius-full);
  object-fit: cover;
  border: 2px solid var(--surface-1);   /* fondan ayırır */
}
.avatar--sm { width: 28px; height: 28px; }
.avatar--md { width: 40px; height: 40px; }
.avatar--lg { width: 56px; height: 56px; }

.avatar-group { display: flex; }
.avatar-group > * + * { margin-left: -10px; }   /* üst-üstə düşmə */
.avatar-group > * { transition: transform 0.2s; }
.avatar-group:hover > * { margin-left: -4px; }  /* hover-də açılır */
```

### 7.7 Statistika bloku

Şəkildəki "34 Deals · 20 won · 3 lost" sırası.

```tsx
<div className="stat">
  <span className="stat__value">34</span>
  <span className="stat__badge stat__badge--up">+3</span>
  <span className="stat__label">Deals</span>
</div>
```

```css
.stat__value { font-size: var(--text-display); font-weight: 600; letter-spacing: -0.04em; }
.stat__label { font-size: 14px; color: var(--text-secondary); }
.stat__badge {
  height: 20px; padding: 0 7px;
  border-radius: var(--radius-full);
  font-size: 11px; font-weight: 600;
}
.stat__badge--up   { background: var(--lime-500); color: var(--text-on-lime); }
.stat__badge--down { background: var(--danger);   color: #fff; }
```

Rəqəm **count-up** ilə animasiya olunur (bax `DESIGN-SYSTEM.md` §4.8), lakin **800ms**, 2s deyil.

### 7.8 Timeline / cədvəl zolağı

Şəkildəki üstdəki yaşıl zolaq — vaxt oxu üzərində avatarlar.

```css
.timeline {
  position: relative;
  height: 44px;
  border-radius: var(--radius-full);
  background: var(--lime-500);
  display: flex; align-items: center;
}
.timeline__now {           /* cari vaxt göstəricisi */
  position: absolute;
  width: 2px; height: 100%;
  background: var(--text-on-lime);
}
.timeline__item {
  position: absolute;      /* left: % — vaxta görə */
  transform: translateX(-50%);
}
```

**IdeaNest-də istifadə:** kampaniya vaxt xətti — launch → hədəfə çatma → son tarix → payout.

### 7.9 Üzən panel (Floating Panel)

Şəkildəki "Summary" və video zəng panelləri.

```css
.floating-panel {
  background: #fff;
  color: var(--text-on-white);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-float);
  overflow: hidden;
}
.floating-panel__header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 20px 20px 12px;
}
.floating-panel__body { padding: 0 20px 20px; }
```

Giriş animasiyası: `slideUp` (bax `DESIGN-SYSTEM.md` §4.11) — `translateY(1.5rem)` + `opacity`, 400ms.

### 7.10 Sol ikon rail (naviqasiya)

```css
.rail {
  width: 72px;
  display: flex; flex-direction: column; align-items: center;
  gap: 12px;
  padding: 24px 0;
}
.rail__item {
  width: 44px; height: 44px;
  border-radius: var(--radius-full);
  display: grid; place-items: center;
  color: var(--text-tertiary);
  transition: all 0.2s ease-in-out;
}
.rail__item:hover        { background: var(--surface-3); color: var(--text-primary); }
.rail__item[data-active] { background: var(--surface-4); color: var(--lime-500); }
```

Aktiv element **yaşıl ikon** alır — fon yox. Yaşıl fonu böyük səthlər üçün saxlayın.

### 7.11 Proqres bar (IdeaNest-ə xas)

Şəkildə yoxdur, bizə lazımdır.

```css
.progress {
  height: 6px;
  border-radius: var(--radius-full);
  background: var(--surface-3);
  overflow: hidden;
}
.progress__fill {
  height: 100%;
  border-radius: var(--radius-full);
  background: var(--lime-500);
  transition: width 0.8s cubic-bezier(0.4, 0, 0.2, 1);
}
.progress--complete .progress__fill {
  background: var(--success);
  box-shadow: 0 0 12px var(--lime-glow);   /* 100%+ olduqda parıltı */
}
```

### 7.12 Bölmə başlığı (Section Rail Header)

```
Bölmə adı    (7 Leads)          [🔍] [⇅]    [All][Hot][...]
─────────────────────────────────────────────────────────────
```

```tsx
<header className="rail-header">
  <h2 className="rail-header__title">Yeni Layihələr</h2>
  <span className="rail-header__count">7 layihə</span>
  <div className="rail-header__actions">
    <IconButton icon={Search} size="sm" />
    <IconButton icon={SlidersHorizontal} size="sm" />
  </div>
  <ChipRow items={filters} />
</header>
```

Sayğac (`7 Leads`) `--text-tertiary` və `--text-xs` — başlıqla rəqabət etmir.

---

## 8. IdeaNest Ekranlarına Tətbiq

### 8.1 Rəng-vəziyyət xəritəsi

Bu, sistemin ən vacib hissəsidir — hansı rəng nəyi bildirir:

| Vəziyyət | Səth | Nümunə |
|---|---|---|
| Adi layihə kartı | `--surface-2` | Discovery şəbəkəsi |
| **Bitməyə 48 saat qalıb** | `--lime-500` | Təcili — diqqət tələb edir |
| **Hədəfə çatdı** | `--surface-2` + `--success` proqres | Uğur, lakin təcili deyil |
| Projects We Love | `--surface-2` + lime sərhəd | `border: 1px solid var(--lime-700)` |
| Trend / Hot | `--surface-2` + `--hot` ikon | Alov ikonu |
| **Ödəniş uğursuz** | `--surface-2` + `--danger` sol zolaq | Banner + kart |
| Layihə dayandırılıb | `--surface-2` + 50% opacity | Passiv |
| Seçilmiş mükafat pilləsi | `--lime-500` | Aktiv seçim |
| Modal / checkout | `--white-surface` | Fokus — sistemin üstündə |

> **Prinsip:** Yaşıl **təcililik** deməkdir, uğur yox. Uğur üçün `--success` var. Bunları qarışdırsanız, istifadəçi yaşılı görüb "hər şey yaxşıdır" deyə qərar verəcək, halbuki mesaj "tələs" idi.

### 8.2 Discovery ekranı

```
┌──┬─────────────────────────────────────────────────┐
│  │  [Axtar həbi]              [Valyuta] [Profil]   │
│  │─────────────────────────────────────────────────│
│i │  Kəşf et                                        │
│k │  [Hamısı][Texnologiya][Oyunlar][Dizayn][...]    │  ← filtr çipləri
│o │                                                 │
│n │  Bitməyə az qalıb   (24)     [🔍][⇅]           │
│  │  ┌──────┐┌──────┐┌ LIME ┐┌──────┐          →   │
│r │  │şəkil ││şəkil ││şəkil ││şəkil │               │
│a │  │ad    ││ad    ││ad    ││ad    │               │
│i │  │▓▓▓░░ ││▓▓▓▓░ ││▓▓▓▓▓ ││▓▓░░░ │  ← proqres    │
│l │  └──────┘└──────┘└──────┘└──────┘               │
│  │                                                 │
│  │  Projects We Love   (12)     [🔍][⇅]           │
│  │  ┌──────┐┌──────┐┌──────┐                  →   │
└──┴─────────────────────────────────────────────────┘
```

Layihə kartında şəkil **tam enli, yuxarıda**, radius yalnız üst künclərdə. Qara fon layihə şəkillərini gücləndirir — bu, sistemin ən böyük üstünlüyüdür.

### 8.3 Creator Dashboard

Şəkildəki dizayna **ən yaxın** ekran. Birbaşa tətbiq:

| Şəkildəki | IdeaNest-də |
|---|---|
| Cədvəl zolağı (yaşıl timeline) | Kampaniya vaxt xətti: launch → hədəf → deadline |
| "34 Deals / 20 won / 3 lost" | "1,697 backer / 1,111% / 26 gün" |
| "New Leads" rail | "Son vədlər" — real vaxtda gələn |
| "Your Days Tasks" rail | "Gözləyən işlər" — survey göndər, update dərc et |
| Filtr çipləri | Mükafat pilləsi üzrə filtr |
| Yaşıl aktiv kart | Təcili iş (məs. 12 uğursuz ödəniş) |
| Üzən "Summary" paneli | Maliyyə xülasəsi |

### 8.4 Kampaniya hekayəsi (uzun mətn) — istisna qayda

Yuxarıda qeyd etdiyim oxunuş məsələsinin həlli:

```css
.story {
  background: var(--surface-2);        /* #161616 — tam qara deyil */
  border-radius: var(--radius-xl);
  padding: 40px;
  max-width: 68ch;                     /* sətir uzunluğu limiti */
}
.story p {
  color: rgba(255, 255, 255, 0.92);    /* tam ağ deyil — halation azalır */
  font-size: 1.0625rem;                /* 17px */
  line-height: 1.75;
  margin-bottom: 1.5em;
}
.story h2 { color: #fff; }             /* başlıqlar tam ağ qala bilər */
```

Üç dəyişiklik: fon bir az açıq, mətn bir az sönük, sətir aralığı geniş. Sistem eyni qalır, 2000 sözü oxumaq mümkün olur.

### 8.5 Ödəniş / checkout ekranı

**Yeganə yer ki, ağ səth üstünlük təşkil edir.**

Səbəb: pul ödəyən istifadəçi maksimum aydınlıq və tanış kontekst istəyir. Ağ panel + qara mətn burada həm oxunuşu, həm etibarı artırır.

```
┌─────────────────────────────────────┐
│  ● ● ○   Addım 2 / 3                │  ← qara fon üzərində
│                                     │
│  ┌───────────────────────────────┐  │
│  │  AĞ PANEL                     │  │
│  │  Mükafat: Early Bird          │  │
│  │  ─────────────────────────    │  │
│  │  Mükafat        599.00 ₼      │  │
│  │  Çatdırılma      25.00 ₼      │  │
│  │  ─────────────────────────    │  │
│  │  Cəmi           624.00 ₼      │  │
│  │                               │  │
│  │  [ Ödənişi təsdiqlə ]  ← lime │  │
│  └───────────────────────────────┘  │
└─────────────────────────────────────┘
```

Təsdiq düyməsi **lime** — səhifədəki yeganə yaşıl element. Diqqət tam ora yönəlir.

---

## 9. Əlçatanlıq Qaydaları

### 9.1 Kontrast yoxlama nəticələri

| Kombinasiya | Nisbət | Nəticə |
|---|---|---|
| `#FFFFFF` / `#0D0D0D` | 20.4:1 | ✅ AAA |
| `rgba(255,255,255,.64)` / `#0D0D0D` | 9.2:1 | ✅ AAA |
| `rgba(255,255,255,.40)` / `#0D0D0D` | 4.9:1 | ✅ AA (yalnız ≥16px) |
| `#0A0A0A` / `#C6F432` (lime səth) | 15.8:1 | ✅ AAA |
| `#C6F432` / `#0D0D0D` (lime mətn, qarada) | 15.4:1 | ✅ AAA |
| **`#C6F432` / `#FFFFFF`** | **1.3:1** | ❌ **QADAĞAN** |
| `#FF4438` / `#0D0D0D` | 5.1:1 | ✅ AA |
| `#34D058` / `#0D0D0D` | 8.9:1 | ✅ AAA |

### 9.2 Qadağalar

| ❌ Etməyin | ✅ Bunun əvəzinə |
|---|---|
| Ağ fonda yaşıl mətn | Yaşıl fon + qara mətn |
| Yaşıl fonda ağ mətn | Yaşıl fon + `#0A0A0A` mətn |
| Yalnız rənglə məlumat ötürmək | Rəng + ikon + mətn |
| `--text-disabled` ilə oxunmalı mətn | Ən azı `--text-tertiary` |
| Tünd kartda kölgə | Sərhəd `rgba(255,255,255,.08)` |
| Uzun mətn tam qara fonda ağ ilə | `--surface-2` + `rgba(255,255,255,.92)` |

### 9.3 Fokus göstəricisi

Tünd temada fokus halqası **mütləq görünməlidir**:

```css
:focus-visible {
  outline: 2px solid var(--lime-500);
  outline-offset: 2px;
  border-radius: inherit;
}
/* Yaşıl səth üzərində fokus — yaşıl görünməz, qara istifadə et */
.card--active :focus-visible,
.pill--accent:focus-visible {
  outline-color: var(--text-on-lime);
}
```

### 9.4 Digər tələblər

- **Nöqtə göstəricisi** (●●●○○) tək başına məlumat daşımamalıdır — yanında faiz rəqəmi olsun
- **Status rəngləri** ikonla müşayiət olunsun (dəngdaltonizm)
- `prefers-reduced-motion` — bax `DESIGN-SYSTEM.md` §9.2
- OLED ekranlarda tam `#000` fon scroll zamanı "smearing" yarada bilər → əsas fon üçün **`#0D0D0D`** tövsiyə olunur, `#000` yalnız kənar boşluqlar üçün
- `color-scheme: dark` meta — brauzer scrollbar və form elementlərini uyğunlaşdırır

---

## 10. Tailwind Konfiqurasiyası

### 10.1 CSS dəyişənləri

```css
/* packages/design-tokens/theme.css */
@layer base {
  :root {
    color-scheme: dark;

    /* Neytral */
    --black: #000000;
    --surface-1: #0D0D0D;
    --surface-2: #161616;
    --surface-3: #1F1F1F;
    --surface-4: #2A2A2A;
    --border: 255 255 255 / 0.08;
    --border-strong: 255 255 255 / 0.16;

    /* Mətn */
    --text-primary: #FFFFFF;
    --text-secondary: rgb(255 255 255 / 0.64);
    --text-tertiary: rgb(255 255 255 / 0.40);
    --text-disabled: rgb(255 255 255 / 0.24);
    --text-on-lime: #0A0A0A;
    --text-on-white: #0A0A0A;

    /* Lime */
    --lime-300: #DCFB7A;
    --lime-400: #D2F95C;
    --lime-500: #C6F432;
    --lime-600: #B0DE1E;
    --lime-700: #94BC15;
    --lime-glow: rgb(198 244 50 / 0.24);

    /* Status */
    --success: #34D058;
    --warning: #FFB020;
    --danger:  #FF4438;
    --info:    #4A9EFF;
    --hot:     #FF6B35;

    /* Radius */
    --radius-sm: 10px;
    --radius-md: 14px;
    --radius-lg: 20px;
    --radius-xl: 28px;
    --radius-full: 9999px;

    /* Kölgə — yalnız ağ panellər */
    --shadow-panel: 0 8px 32px -8px rgb(0 0 0 / 0.5);
    --shadow-float: 0 24px 64px -12px rgb(0 0 0 / 0.7);

    /* Hərəkət — DESIGN-SYSTEM.md ilə eyni */
    --transition-fast: 0.15s ease-in-out;
    --transition-base: 0.3s ease-in-out;
    --transition-slow: 0.5s ease-in-out;
    --ease-standard: cubic-bezier(0.4, 0, 0.2, 1);
  }
}
```

### 10.2 Tailwind 4 tema

```css
/* apps/web/src/app/globals.css */
@import 'tailwindcss';
@import '@ideanest/design-tokens/theme.css';

@theme {
  --color-surface-1: var(--surface-1);
  --color-surface-2: var(--surface-2);
  --color-surface-3: var(--surface-3);
  --color-surface-4: var(--surface-4);

  --color-lime-300: var(--lime-300);
  --color-lime-400: var(--lime-400);
  --color-lime-500: var(--lime-500);
  --color-lime-600: var(--lime-600);

  --color-success: var(--success);
  --color-warning: var(--warning);
  --color-danger:  var(--danger);
  --color-hot:     var(--hot);

  --radius-sm: 10px;
  --radius-md: 14px;
  --radius-lg: 20px;
  --radius-xl: 28px;

  --font-display: 'General Sans', system-ui, sans-serif;
  --font-sans: 'Inter', system-ui, sans-serif;
}
```

İstifadə:

```tsx
<article className="rounded-lg border border-white/8 bg-surface-2 p-5
                    transition-colors duration-300 hover:bg-surface-3">
  <h3 className="text-lg font-medium tracking-tight text-white">{project.title}</h3>
  <p className="mt-1 text-sm text-white/64">{project.creator.name}</p>
  <div className="mt-4 h-1.5 overflow-hidden rounded-full bg-surface-3">
    <div className="h-full rounded-full bg-lime-500 transition-[width] duration-800"
         style={{ width: `${Math.min(percent, 100)}%` }} />
  </div>
</article>
```

### 10.3 Mobil (NativeWind)

Eyni tokenlər `packages/design-tokens/tokens.ts`-dən oxunur:

```ts
export const colors = {
  surface1: '#0D0D0D',
  surface2: '#161616',
  surface3: '#1F1F1F',
  surface4: '#2A2A2A',
  lime500:  '#C6F432',
  textPrimary: '#FFFFFF',
  textSecondary: 'rgba(255,255,255,0.64)',
  textOnLime: '#0A0A0A',
  success: '#34D058',
  danger:  '#FF4438',
} as const;
```

Mobil üçün əlavə: **StatusBar `light-content`**, splash screen fonu `#0D0D0D`, `app.config.ts`-də `userInterfaceStyle: 'dark'`.

---

## 11. Nixtio Sənədi ilə Uzlaşdırma

`DESIGN-SYSTEM.md` Nixtio analizinə əsaslanır və **açıq tema** (`#f5f5f5` fon) təklif edirdi. Bu sənəd onu **əvəz edir** — lakin yalnız qismən.

| Bölmə | Vəziyyət |
|---|---|
| `DESIGN-SYSTEM.md` §1–4 — **hərəkət sistemi** | ✅ **Qüvvədə qalır** — fade-up, stagger, 3D flip düymə, page transition, marquee, count-up |
| `DESIGN-SYSTEM.md` §5 — **rəng və səth** | ❌ **Ləğv olunur** → bu sənəd (§2, §3) |
| `DESIGN-SYSTEM.md` §5.2 — **tipoqrafiya (clamp, letter-spacing)** | ✅ Prinsip qalır, dəyərlər bu sənəddə yenilənib (§5) |
| `DESIGN-SYSTEM.md` §5.3 — **radius** | ❌ Ləğv → bu sənəd §4 (5 dəyər, 3 deyil) |
| `DESIGN-SYSTEM.md` §7.3 — **hərəkət büdcəsi** | ✅ **Qüvvədə qalır** — çox vacib |
| `DESIGN-SYSTEM.md` §7.4 — **rəng tokenləri** | ❌ Ləğv → bu sənəd §10 |
| `DESIGN-SYSTEM.md` §8 — **mobil qarşılıqlar** | ✅ Qalır |
| `DESIGN-SYSTEM.md` §9 — **performans və a11y** | ✅ Qalır + bu sənəd §9 əlavə edir |

### 11.1 Yekun formula

> **Nixtio-nun hərəkət intizamı** (bir animasiya tipi, 300ms baza, 50ms stagger, pul axınına yaxınlaşdıqca hərəkət azalır)
> **+**
> **Bu dashboard-un vizual dili** (qara fon, ağ vurğu, lime = təcililik, həb formaları, kölgəsiz elevation)

İkisi bir-birinə zidd deyil — Nixtio *necə hərəkət etməli*, bu sənəd *necə görünməli* sualına cavab verir.

### 11.2 Bir cümlədə

> Qara səhnə, ağ işıq, yaşıl siqnal. Hərəkət az, forma yumşaq, iyerarxiya rənglə.

---

## Əlavə — Tətbiq ardıcıllığı

| Addım | İş | Asılılıq |
|---|---|---|
| 1 | `packages/design-tokens` — bu sənəddəki bütün tokenlər | — |
| 2 | Şrift seçimi + `ə ğ ı ö ş ü ç` gözlə yoxlama | — |
| 3 | Primitivlər: `Card`, `Pill`, `Chip`, `IconButton`, `Tag`, `Avatar` | 1, 2 |
| 4 | Kompozitlər: `StatBlock`, `ProgressBar`, `RailHeader`, `ChipRow` | 3 |
| 5 | Layout: `Rail` (sol naviqasiya), `TopBar`, `CardRail` (üfüqi scroll) | 3 |
| 6 | `FloatingPanel` + modal sistemi | 3 |
| 7 | Hərəkət qatı (`DESIGN-SYSTEM.md` §10.3 ardıcıllığı) | 3–6 |
| 8 | Discovery və Dashboard ekranlarının qurulması | 1–7 |
| 9 | Kontrast auditi + `prefers-reduced-motion` yoxlaması | 8 |

**Storybook tövsiyə olunur** — 12 komponentin 3-4 variantı var, vizual reqressiya testi olmadan sistem sürüşəcək.
