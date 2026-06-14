// =======================================================
// OBUDOWA HERMETYCZNA DO SZKLARNI (Wersja V4 - Przemysłowa)
// Montaż płaski (Vertical Wall Mount), wszystkie wejścia od dołu
// =======================================================

$fn = 60;

// --- PARAMETRY ---
dlugosc = 135;  // Szerokość na ścianie (X)
szerokosc = 85; // Wysokość na ścianie (Y)
wysokosc = 45;  // Głębokość / Odstawanie od ściany (Z)
grubosc = 2.5;

// =======================================================
// 1. SKRZYNKA GŁÓWNA (Drukuj płasko)
// =======================================================
module skrzynka_glowna() {
    difference() {
        union() {
            // Główny korpus
            cube([dlugosc, szerokosc, wysokosc]);
            
            // Lewe ucho montażowe (Na plecach, Z=0)
            translate([-15, szerokosc/2 - 12.5, 0]) cube([15, 25, 6]);
            // Prawe ucho montażowe (Na plecach, Z=0)
            translate([dlugosc, szerokosc/2 - 12.5, 0]) cube([15, 25, 6]);
            
            // 4 Słupki montażowe w rogach na pokrywkę
            translate([6, 6, grubosc]) cylinder(d=8, h=wysokosc-grubosc);
            translate([dlugosc-6, 6, grubosc]) cylinder(d=8, h=wysokosc-grubosc);
            translate([6, szerokosc-6, grubosc]) cylinder(d=8, h=wysokosc-grubosc);
            translate([dlugosc-6, szerokosc-6, grubosc]) cylinder(d=8, h=wysokosc-grubosc);
        }

        // Wydrążenie wnętrza
        translate([grubosc, grubosc, grubosc])
            cube([dlugosc - 2*grubosc, szerokosc - 2*grubosc, wysokosc]);

        // Otwory na śruby do ściany w uszach (d=5mm)
        translate([-7.5, szerokosc/2, -1]) cylinder(d=5.2, h=10);
        translate([dlugosc+7.5, szerokosc/2, -1]) cylinder(d=5.2, h=10);

        // Otwory na śruby w słupkach na pokrywkę (d=2.5mm pod małe wkręty)
        translate([6, 6, -1]) cylinder(d=2.5, h=50);
        translate([dlugosc-6, 6, -1]) cylinder(d=2.5, h=50);
        translate([6, szerokosc-6, -1]) cylinder(d=2.5, h=50);
        translate([dlugosc-6, szerokosc-6, -1]) cylinder(d=2.5, h=50);

        // ----------------------------------------------------
        // OTWORY NA DOLNEJ ŚCIANCE (Skierowane do ziemi: Y=0)
        // ----------------------------------------------------
        
        // 1. Dławnica PG7 (Czujnik gleby)
        translate([20, -1, wysokosc/2]) rotate([-90, 0, 0]) cylinder(d=12.5, h=10);
        
        // 2. Klatka SHT41 - Otwór centralny na kabel
        translate([55, -1, wysokosc/2]) rotate([-90, 0, 0]) cylinder(d=12, h=10);
        
        // 3. Klatka SHT41 - Śrubki montażowe (Rozstaw 32mm)
        translate([39, -1, wysokosc/2]) rotate([-90, 0, 0]) cylinder(d=3.2, h=10);
        translate([71, -1, wysokosc/2]) rotate([-90, 0, 0]) cylinder(d=3.2, h=10);
        
        // 4. Dławnica PG9 (230V Zasilanie)
        translate([110, -1, wysokosc/2]) rotate([-90, 0, 0]) cylinder(d=15.5, h=10);
    }

    // --- BEZPIECZNA PRZEGRODA (ESP32 vs 230V) ---
    difference() {
        // Ścianka na X=85
        translate([85, grubosc, grubosc])
            cube([grubosc, szerokosc - 2*grubosc, wysokosc - grubosc]);
        
        // Przepust u góry ścianki (daleko od dolnych wejść kablowych) na kabelki sterujące
        translate([84, szerokosc - 20, grubosc])
            cube([grubosc + 2, 15, 15]);
    }
}

// =======================================================
// 2. KLATKA SHT41 (Drukuj pionowo, kołnierzem do stołu)
// =======================================================
module klatka_sht41() {
    cage_height = 40;
    cage_outer_d = 32;
    cage_inner_d = 26;
    kolnierz_d = 42; // Idealnie pasuje na dno (szerokość 45mm)
    kolnierz_h = 3;

    difference() {
        union() {
            cylinder(d=kolnierz_d, h=kolnierz_h); // Płaski kołnierz przylegający do puszki
            cylinder(d=cage_outer_d, h=cage_height);
        }

        // Wydrążenie wnętrza na wylot
        translate([0, 0, -1]) cylinder(d=cage_inner_d, h=cage_height + 2);

        // Dwa otwory w kołnierzu pasujące do dna puszki (Rozstaw 32mm -> odległość 16 z każdej)
        translate([-16, 0, -1]) cylinder(d=3.2, h=kolnierz_h + 2);
        translate([16, 0, -1]) cylinder(d=3.2, h=kolnierz_h + 2);

        // Wycięcia wentylacyjne (Żaluzje pod kątem 15 stopni - Zero podpór)
        for (z = [10, 18, 26, 34]) {
            for (rot = [0, 90, 180, 270]) {
                rotate([0, 0, rot])
                    translate([0, -cage_outer_d/2 - 1, z])
                        rotate([15, 0, 0])
                            cube([12, cage_outer_d + 2, 3.5], center=true);
            }
        }
    }
}

// =======================================================
// 3. POKRYWKA (LID) Z OTWORAMI NA ŚRUBY
// =======================================================
module pokrywka() {
    difference() {
        // Główna płytka
        cube([dlugosc, szerokosc, grubosc]);
        
        // 4 otwory na śruby montażowe w rogach
        translate([6, 6, -1]) cylinder(d=3.2, h=10);
        translate([dlugosc-6, 6, -1]) cylinder(d=3.2, h=10);
        translate([6, szerokosc-6, -1]) cylinder(d=3.2, h=10);
        translate([dlugosc-6, szerokosc-6, -1]) cylinder(d=3.2, h=10);
    }
    
    // Wewnętrzny kołnierz pozycjonujący
    luz = 0.4;
    translate([grubosc + luz, grubosc + luz, grubosc])
        difference() {
            cube([dlugosc - 2*grubosc - 2*luz, szerokosc - 2*grubosc - 2*luz, 3]);
            // Wydrążenie środka kołnierza
            translate([2, 2, -1]) cube([dlugosc - 2*grubosc - 2*luz - 4, szerokosc - 2*grubosc - 2*luz - 4, 5]);
            // Wycięcia na słupki montażowe w rogach
            translate([-1, -1, -1]) cylinder(d=12, h=5);
            translate([dlugosc-8, -1, -1]) cylinder(d=12, h=5);
            translate([-1, szerokosc-8, -1]) cylinder(d=12, h=5);
            translate([dlugosc-8, szerokosc-8, -1]) cylinder(d=12, h=5);
        }
}

// =======================================================
// WYRENDERUJ WSZYSTKO (Rozłożone płasko do druku 3D)
// =======================================================

// 1. Skrzynka
skrzynka_glowna();

// 2. Klatka (Obok skrzynki)
translate([dlugosc + 40, 25, 0]) klatka_sht41();

// 3. Pokrywka (Nad skrzynką)
translate([0, szerokosc + 10, 0]) pokrywka();