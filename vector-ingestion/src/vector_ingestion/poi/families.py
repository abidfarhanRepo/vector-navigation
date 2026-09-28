"""POI semantic families: what a feature IS.

Raw Overture ``category`` values (781 distinct in the Qatar census) and OSM
``poi_class`` values (312 distinct) are reduced to a small set of driver-facing
families. The mapping is built from a census of the actual data
(``.bootstrap-cache/qatar_places.geojson`` and the OSM basemap), not from a
wish list — every significant value has a row here, and everything else falls
to UNKNOWN.

Rules:

* **Unknown is a family, not an error.** An unrecognised category is retained,
  conservatively low-ranked, and never deleted. Being wrong about a category
  must cost a POI its rank, never its existence.
* Families are "what the feature IS", orthogonal to "is it useful as a
  destination" — RESIDENTIAL, OFFICE, INDUSTRY and MAP_FURNITURE are real
  families whose members are nevertheless excluded by default (see
  ``poi/score.py``), while a handful of infrastructure classes that ARE
  destinations (parking, fuel, transit, police, fire) live in KEPT families.
* `landmark_and_historical_building` is Overture's dumping ground (583
  records measured: Ezdan Villages, Barwa compounds, bare zone names like
  "Street 11"): it is resolved by NAME context so the real landmarks keep
  their family and the residential junk does not ride along.
"""

from __future__ import annotations

# -- families -----------------------------------------------------------------

# Driver-facing families (destination-worthy by default)
DESTINATION = "DESTINATION"
FOOD = "FOOD"
HEALTHCARE = "HEALTHCARE"
SHOPPING = "SHOPPING"
RELIGIOUS = "RELIGIOUS"
EDUCATION = "EDUCATION"
TRANSPORT = "TRANSPORT"
GOVERNMENT = "GOVERNMENT"
LODGING = "LODGING"
ATTRACTION = "ATTRACTION"
SPORT = "SPORT"
PARKING = "PARKING"
FINANCE = "FINANCE"
SERVICES = "SERVICES"

# Non-destination families (excluded by default, see score.py)
RESIDENTIAL = "RESIDENTIAL"
OFFICE = "OFFICE"
INDUSTRY = "INDUSTRY"
MAP_FURNITURE = "MAP_FURNITURE"
INFRASTRUCTURE = "INFRASTRUCTURE"

# Conservative catch-all: retained, low rank, never excluded by itself.
UNKNOWN = "UNKNOWN"

ALL_FAMILIES = (
    DESTINATION, FOOD, HEALTHCARE, SHOPPING, RELIGIOUS, EDUCATION, TRANSPORT,
    GOVERNMENT, LODGING, ATTRACTION, SPORT, PARKING, FINANCE, SERVICES,
    RESIDENTIAL, OFFICE, INDUSTRY, MAP_FURNITURE, INFRASTRUCTURE, UNKNOWN,
)

#: Families whose members are excluded as NON_DESTINATION by default.
#: A member may still be RETAINED (rescued) when cross-source confirmed — a
#: surveyed OSM presence is evidence the place exists on the ground.
EXCLUDED_FAMILIES = frozenset({RESIDENTIAL, OFFICE, INDUSTRY,
                               MAP_FURNITURE, INFRASTRUCTURE})

#: Institution types that exist ONCE per name — a church, embassy or museum
#: has one real location, so a same-name variant far from the highest-quality
#: copy is evidence of a duplicate pin, not of a second branch. Chains are
#: deliberately absent: "First Dental Center" has two real branches 10 km
#: apart (measured) and nothing may merge those.
SINGLETON_FAMILIES = frozenset({RELIGIOUS, GOVERNMENT, ATTRACTION, HEALTHCARE})
SINGLETON_HEALTHCARE = frozenset({"hospital"})
SINGLETON_CLASSES = {
    RELIGIOUS: frozenset({
        "mosque", "church_cathedral", "catholic_church", "anglican_church",
        "pentecostal_church", "evangelical_church", "religious_organization",
        "place_of_worship", "grave_yard", "cemetery",
    }),
    GOVERNMENT: frozenset({
        "embassy", "consulate", "diplomatic", "high_commission",
        "royal_embassy"
    }),
    ATTRACTION: frozenset({
        "museum", "art_museum", "history_museum", "childrens_museum",
        "castle", "palace", "fort", "monument", "amusement_park", "zoo",
        "aquarium", "planetarium", "observatory", "national_park",
        "botanical_garden", "archaeological_site",
    }),
}


def is_singleton_family(family: str, raw_category: "str | None" = None) -> bool:
    """Is this family one member-per-name (so far copies are suspicious)?

    Hospitals are NOT blanket singletons — Hamad has many facilities — so
    only explicit ``hospital``-named HEALTHCARE records are candidates, and
    only through the narrow typo rule; a same-name copy kilometres away stays
    a second branch.
    """
    if family == HEALTHCARE:
        return bool(raw_category) and raw_category in SINGLETON_HEALTHCARE
    if family in SINGLETON_CLASSES:
        if not raw_category:
            return family == RELIGIOUS
        return raw_category in SINGLETON_CLASSES[family]
    return family in SINGLETON_FAMILIES


# -- Overture category -> family ----------------------------------------------
# Built from the full 781-category census (2026-09-13). Values not listed fall
# to the suffix rules below, then to UNKNOWN.

_FOOD = {
    "restaurant", "coffee_shop", "cafe", "fast_food_restaurant",
    "indian_restaurant", "pizza_restaurant", "middle_eastern_restaurant",
    "turkish_restaurant", "burger_restaurant", "asian_restaurant", "bakery",
    "ice_cream_shop", "seafood_restaurant", "sandwich_shop",
    "filipino_restaurant", "american_restaurant", "desserts", "chocolatier",
    "smoothie_juice_bar", "eat_and_drink", "pakistani_restaurant", "steakhouse",
    "breakfast_and_brunch_restaurant", "butcher_shop", "fruits_and_vegetables",
    "barbecue_restaurant", "tea_room", "cupcake_shop", "health_food_restaurant",
    "coffee", "donuts", "bubble_tea", "vegetarian_restaurant", "mexican_restaurant",
    "bar_and_grill_restaurant", "sushi_restaurant", "asian_fusion_restaurant",
    "bangladeshi_restaurant", "pizza_delivery_service", "buffet_restaurant",
    "diner", "gastropub", "pub", "bar", "lounge", "dance_club", "wine_bar",
    "hookah_bar", "sports_bar", "cocktail_bar", "beer_bar", "cigar_bar",
    "nightclub", "jazz_and_blues", "karaoke", "tapas_bar", "whiskey_bar",
    "tiki_bar", "milk_bar", "hotel_bar", "coffee_and_tea_supplies",
    "cafeteria", "gelato", "bagel_shop", "brasserie", "food_court",
    "deli", "patisserie_cake_shop", "cheese_shop", "kept",
    "frozen_yoghurt_shop", "pancake_house", "doner_kebab", "food_truck",
    "food", "hot_dog_restaurant", "smoothie_bar",
}

_HEALTHCARE = {
    "hospital", "pharmacy", "medical_center", "dentist", "health_and_medical",
    "diagnostic_services", "clinic", "veterinarian", "veterinary",
    "laboratory_testing", "laboratory", "surgical_appliances_and_supplies",
    "medical_supply", "skilled_nursing", "emergency_room", "physician",
    "dermatologist", "orthodontist", "cosmetic_dentist", "public_health_clinic",
    "physical_therapy", "chiropractor", "eye_care_clinic", "fertility",
    "pediatrician", "obstetrician_and_gynecologist", "cardiologist", "urologist",
    "podiatry", "psychiatrist", "psychologist", "optometrist", "audiologist",
    "endodontist", "prosthodontist", "orthopedist", "ear_nose_and_throat",
    "nursing_home", "home_health_care", "medical_service_organizations",
    "medical_research_and_development", "abortion_clinic",
    "blood_and_plasma_donation_center", "assisted_living_facility",
    "disability_services_and_support_organization", "weight_loss_center",
    "rehabilitation", "retirement_home", "physiotherapist", "therapist",
    "health", "healthcare", "nutritionist", "nutrition_supplements",
    "family_practice", "general_dentistry", "naturopathic_holistic",
    "womens_health_clinic", "maternity_centers", "plastic_surgeon",
}

_SHOPPING = {
    "clothing_store", "grocery_store", "shopping", "jewelry_store",
    "womens_clothing_store", "shopping_center", "furniture_store",
    "mobile_phone_store", "electronics", "department_store", "shoe_store",
    "boutique", "convenience_store", "toy_store", "computer_store",
    "mens_clothing_store", "fashion", "lighting_store", "home_improvement_store",
    "carpet_store", "hardware_store", "perfume_store", "candy_store",
    "sporting_goods", "sports_wear", "childrens_clothing_store", "watch_store",
    "home_goods_store", "bookstore", "bicycle_shop", "uniform_store",
    "discount_store", "lingerie_store", "fabric_store", "mattress_store",
    "health_food_store", "appliance_store", "outdoor_gear", "souvenir_shop",
    "musical_instrument_store", "sunglass_store", "luggage_store",
    "video_game_store", "florist", "flowers_and_gifts_shop",
    "nail_salon", "hair_supply_stores", "supermarket", "superstore",
    "thrift_store", "outlet_store", "pop_up_shop", "duty_free_shop",
    "antique_store", "hobby_shop", "home_decor", "handbag_stores",
    "leather_goods", "costume_store", "cards_and_stationery_store",
    "comic_books_store", "books_mags_music_and_video", "hat_shop",
    "maternity_wear", "baby_gear_and_furniture", "kitchen_supply_store",
    "bedding_and_bath_stores", "shades_and_blinds", "curtain",
    "bathroom_furnishing", "houseware", "hifi", "radiotechnics", "electronics",
    "electric_supply_store", "electrical_supply_store", "tiles", "paint",
    "hardware", "doityourself", "home_and_garden", "organic_grocery_store",
    "grocery", "general", "variety_store", "minimarket", "market",
    "fishmonger", "greengrocer", "newsagent", "kiosk", "pharmacy_shop",
    "cosmetic_and_beauty_supplies", "eyewear_and_optician",
    "fashion_accessories_store", "nursery_and_gardening", "arts_and_crafts",
    "vitamins_and_supplements", "sunglasses_store", "beauty_product_supplier",
    "hunting_and_fishing_supplies", "tobacco_shop", "bridal_shop",
    "music_and_dvd_store", "designer_clothing", "swimwear_store",
    "aquatic_pet_store", "audio_visual_equipment_store",
    "boat_parts_and_supply_store", "linen", "delicatessen",
}

_TRANSPORT = {
    "gas_station", "airport", "metro_station", "transportation",
    "light_rail_and_subway_stations", "bus_station", "train_station",
    "airport_terminal", "heliports", "ferry_boat_company", "marina", "pier",
    "taxi_service", "taxi", "fuel", "charging_station", "terminal",
    "ferry_terminal", "station", "airline", "airport_lounge", "bus_stop",
    "helipad", "trains",
}

_GOVERNMENT = {
    "central_government_office", "public_service_and_government",
    "police_department", "post_office", "courthouse", "town_hall",
    "public_plaza", "armed_forces_branch", "fire_department",
    "passport_and_visa_services", "embassy", "consulate", "diplomatic",
    "townhall", "fire_station", "police", "government", "prison",
    "coast_guard", "ambulance_station", "ambulance_and_ems_services",
    "police_station",
}

_LODGING = {
    "hotel", "resort", "motel", "hostel", "guest_house", "bed_and_breakfast",
    "holiday_rental_home", "chalet", "cottage", "cabin", "beach_resort",
    "hotel_supply_service", "campground", "camp_site",
}

_ATTRACTION = {
    "beach", "park", "arts_and_entertainment", "stadium_arena", "museum",
    "attraction", "cinema", "amusement_park", "art_gallery", "cultural_center",
    "aquarium", "monument", "castle", "palace", "national_park",
    "public_market", "farmers_market", "flea_market", "night_market",
    "theme_park", "zoo", "wildlife_sanctuary", "nature_reserve",
    "botanical_garden", "hiking_trail", "water_park", "scuba_diving_center",
    "sightseeing_tour_agency", "art_museum", "history_museum",
    "childrens_museum", "planetarium", "observatory", "archaeological_site",
    "fort", "opera_and_ballet", "theatre", "theaters_and_performance_venues",
    "auditorium", "music_venue", "drive_in_theater", "movie_television_studio",
    "museum_shop", "memorial", "viewpoint", "ruins", "waterfall", "lake",
    "comedy_club", "boat_tours",
}

_SPORT = {
    "gym", "spas", "sports_and_recreation_venue", "martial_arts_club",
    "swimming_pool", "tennis_court", "golf_course", "soccer_field",
    "football_stadium", "basketball_court", "playground", "badminton_court",
    "sports_club_and_league", "pilates_studio", "yoga_studio",
    "fitness_trainer", "gymnastics_center", "boxing_class", "sports_hall",
    "sports_centre", "fitness_centre", "swimming_instructor", "race_track",
    "equestrian_facility", "horse_riding", "horseback_riding_service",
    "active_life", "horse_boarding", "indoor_playcenter",
    "health_and_wellness_club",
    "bowling_alley", "pool_billiards", "paintball", "laser_tag",
    "escape_rooms", "arcade", "ice_skating_rink", "diving_center",
    "paddle_tennis_club", "cricket_ground", "soccer_stadium",
    "tennis_stadium", "basketball_stadium", "volleyball_court", "golf_club",
    "fishing_club", "salsa_club", "cycling_classes", "rock_climbing_spot",
    "skate_shop", "tennis_court", "squash_court", "indoor_golf_center",
    "shooting_range", "stadium", "arena", "sports_bar_keeper", "gym_center",
    "sports_center",
}

_PARKING = {
    "parking", "parking_lot", "parking_garage", "parking_space_keep",
    "bicycle_parking", "motorcycle_parking",
}

_FINANCE = {
    "bank_credit_union", "banks", "bank", "atms", "atm", "currency_exchange",
    "bureau_de_change", "money_transfer_services", "financial_service",
    "insurance_agency", "investing", "financial_advising", "installment_loans",
    "brokers", "insurance", "insurance_agent", "money_transfer",
}

_SERVICES = {
    "automotive_repair", "car_dealer", "car_rental_agency", "travel_services",
    "beauty_salon", "dry_cleaning", "laundromat", "hairdresser", "beauty",
    "barber", "hair_salon", "tailor", "car_wash", "auto_detailing",
    "automotive_services_and_repair", "tire_dealer_and_repair", "tyres",
    "car_repair", "car_parts", "auto_parts_and_supply_store", "travel_company",
    "tours", "travel_agents", "personal_care_service", "massage_therapy",
    "medical_spa", "waxing", "tanning_salon", "makeup_artist", "skin_care",
    "laser_hair_removal", "hair_removal", "hair_replacement", "aromatherapy",
    "wellness_program", "life_coach", "pet_store", "pet_services",
    "pet_groomer", "pet_boarding", "animal_boarding", "animal_shelter",
    "photographer", "photo", "event_photography", "session_photography",
    "photography_store_and_services", "photo_studio", "copyshop", "printing",
    "commercial_printer", "print_media", "key_cutter", "locksmith",
    "key_and_locksmith", "shoe_repair", "sewing_and_alterations", "laundry",
    "carpet_cleaning", "appliance_repair_service", "television_service_providers",
    "towing_service", "oil_change_station", "auto_glass_service",
    "auto_body_shop", "auto_customization", "car_window_tinting",
    "auto_restoration_services", "wheel_and_rim_repair",
    "emissions_inspection", "motorcycle_repair", "truck_repair",
    "vehicle_inspection", "car_stereo_store", "auto_upholstery",
    "tire_shop", "mobile_phone_repair", "computer_coaching", "motorcycle",
    "car", "car_repair_shop", "vehicle", "internet_cafe", "wedding_planning",
    "party_and_event_planning", "party_supply", "events_venue",
    "venue_and_event_space", "event_technology_service", "caterer",
    "funeral_services_and_cemeteries", "translating_and_interpreting_services",
    "translation_services", "writing_service", "gift_shop", "market",
    "community_services_non_profits", "charity_organization",
    "non_governmental_association", "foundation", "youth_organizations",
    "social_facility", "community_center", "community_centre", "veterinarian",
    "communal", "safe_store", "luggage_storage", "animal_breeding",
    "car_rental", "auto_company", "auto_dealer", "used_car_dealer",
    "motorcycle_dealer", "boat_dealer", "truck_dealer", "motorsport_vehicle_dealer",
    "mobile_home_dealer", "car_dealership",
    "limo_services", "health_spa", "printing_services", "golf_cart_dealer",
    "phone_repair_service", "car_inspection",
}

_RESIDENTIAL = {
    "accommodation", "apartments", "service_apartments", "housing_authorities",
    "condominium", "mobile_home_park", "apartment", "residential",
}

_OFFICE = {
    "professional_services", "corporate_office", "real_estate_service",
    "real_estate_agent", "property_management", "home_developer",
    "commercial_real_estate", "land_surveying", "accountant",
    "legal_services", "notary_public", "marketing_consultant",
    "food_consultant", "advertising_agency", "marketing_agency",
    "internet_marketing_service", "software_development",
    "information_technology_company", "it_service_and_computer_repair",
    "telecommunications_company", "web_designer", "graphic_designer",
    "engineering_services", "architectural_designer", "structural_engineer",
    "civil_engineers", "media_agency", "media_news_company", "public_relations",
    "human_resource_services", "employment_agencies", "employment_law",
    "immigration_law", "criminal_defense_law", "ip_and_internet_law",
    "business_consulting", "business_management_services",
    "business_advertising", "business_to_business", "business_signage",
    "business_storage_and_transportation", "business_manufacturing_and_supply",
    "b2b_apparel", "b2b_textiles", "b2b_science_and_technology",
    "b2b_electronic_equipment", "b2b_equipment_maintenance_and_repair",
    "lawyer", "insurance_agency", "real_estate", "office", "corporate",
    "organization", "political_organization", "political_party_office",
    "labor_union", "community_services_non_profits_npo", "trusts",
    "coworking_space", "career_counseling", "talent_agency", "auction_house",
    "appraisal_services", "personal_assistant", "nanny_services",
    "house_sitting", "merchandising_service", "bookkeeping",
    "interior_design", "interior_decoration", "computer_hardware_company",
    "social_media_agency", "radio_station", "music_production",
    "broadcasting_media_production", "food_delivery_service",
    "topic_publisher", "content_provider", "media_production",
    "consultancy", "quality_assurance", "administration_services",
}

_INDUSTRY = {
    "construction_services", "contractor", "building_supply_store",
    "wholesale_store", "wholesaler", "wholesale_grocer", "meat_wholesaler",
    "computer_wholesaler", "industrial_company", "industrial_equipment",
    "commercial_industrial", "chemical_plant", "plastic_manufacturer",
    "plastic_company", "metal_fabricator", "metal_supplier",
    "steel_fabricators", "machine_shop", "glass_manufacturer",
    "appliance_manufacturer", "aircraft_manufacturer",
    "jewelry_and_watches_manufacturer", "auto_manufacturers_and_distributors",
    "geological_services", "oil_and_gas",
    "oil_and_gas_exploration_and_development",
    "oil_and_gas_field_equipment_and_services", "b2b_energy_and_mining",
    "logging_contractor", "freight_and_cargo_service",
    "freight_forwarding_agency", "movers", "shipping_center",
    "railroad_freight", "storage_facility", "distribution_services",
    "exporters", "food_and_beverage_exporter", "agricultural_service",
    "agriculture", "farm", "livestock_breeder", "fish_farm", "well_drilling",
    "energy_company", "public_utility_company", "recycling_center",
    "garbage_collection_service", "environmental_and_ecological_services_for_businesses",
    "environmental_conservation_organization", "environmental_testing",
    "water_treatment_equipment_and_services", "water_supplier",
    "bottled_water_company", "vending_machine_supplier", "e_commerce_service",
    "web_hosting_service", "internet_service_provider", "telecommunications",
    "automobile_leasing", "rental_service", "truck_rentals",
    "boat_service_and_repair", "boat_rental_and_training", "sign_making",
    "building_materials", "granite_supplier", "pipe_supplier",
    "countertop_installation", "kitchen_remodeling", "window_construction",
    "windows_installation", "fence_and_gate_sales_service",
    "fireplace_service", "chimney_sweep", "pool_and_hot_tub_services",
    "pool_cleaning", "home_cleaning", "cleaning_services", "janitorial_services",
    "pest_control_service", "landscaping", "gardener", "home_service",
    "home_security", "security_services", "security_systems", "home_automation",
    "water_heater_installation_repair", "tv_mounting", "furniture_assembly",
    "damage_restoration", "electrical", "electrical_supply", "plumbing",
    "masonry_concrete", "paving_contractor", "flooring_contractors",
    "elevator_service", "garage_door_service", "hvac_services",
    "construction_company", "construction_company_applicator_industrialcoatinglining",
    "trade", "wholesale", "industrial", "factory", "manufacturer", "plant",
    "foundry", "weighbridge_keep", "crane_services", "machine_and_tool_rentals",
    "commercial_refrigeration", "scale_supplier", "sandblasting_service",
    "occupational_safety", "safety_equipment", "packing_supply",
    "hotel_supply", "restaurant_equipment_and_supply", "office_equipment",
    "home_improvement", "kitchen_and_bath", "fish_market", "packing",
    "fire_protection_service", "food_beverage_service_distribution",
    "glass_and_mirror_sales_service", "automation_services", "carpenter",
    "electrician", "painting", "agricultural_cooperatives",
    "screen_printing_t_shirt_printing", "pharmaceutical_companies",
    "printing_services_trade", "metal_works", "welding_service",
    "moving_company", "logistics", "courier", "postal", "freight_forward",
}

_MAP_FURNITURE = {
    "shelter", "parking_entrance", "parking_position", "parking_space",
    "camp_pitch", "level_crossing", "tram_level_crossing", "tram_crossing",
    "crossing", "holding_position", "stop_position", "subway_entrance",
    "bench", "gate", "toilets", "shower", "fountain", "drinking_water",
    "watering_place", "waste_basket", "waste_disposal", "bbq", "telephone",
    "post_box", "clock", "smoking_area", "bicycle_parking",
    "motorcycle_parking", "weighbridge", "designated", "surveillance",
    "artwork", "information", "picnic_site", "public_building", "general",
    "fixme", "wreck", "buffer_stop", "windsock", "slipway", "city_gate",
    "quiet_room", "lounger", "hunting_stand", "payment_terminal", "switch",
    "canal", "mountain", "grave_yard_site", "toilets_public",
}

_INFRASTRUCTURE = {
    "electric_utility_provider", "water_utility", "public_utility_company",
    "bridge", "structure_and_geography", "tower_keep", "power", "substation",
    "landfill", "quarry", "dam", "water_point", "water_tower", "waste_transfer_station",
    "recycling_center_keep", "plant_keep", "pipeline", "mast",
}

_EDUCATION = {
    "school", "preschool", "college_university", "education",
    "educational_services", "elementary_school", "high_school",
    "campus_building", "private_school", "kindergarten", "language_school",
    "driving_school", "music_school", "art_school", "specialty_school",
    "vocational_and_technical_school", "middle_school", "public_school",
    "educational_research_institute", "cooking_school", "cosmetology_school",
    "tutoring_center", "test_preparation", "medical_school", "university",
    "college", "childcare", "day_care_preschool", "child_care_and_day_care",
    "educational_institution", "library", "religious_school", "nursery_school",
    "school_sports_team", "dance_school", "flight_school",
}

_RELIGIOUS = {
    "mosque", "church_cathedral", "catholic_church", "anglican_church",
    "pentecostal_church", "evangelical_church", "religious_organization",
    "religious_center", "cemetery", "grave_yard", "mosque_religious",
    "church", "chapel", "temple", "synagogue", "shrine", "masjid",
}

# -- OSM poi_class -> family ---------------------------------------------------

_OSM_KEYS = {
    # explicit rows for OSM vocabulary; suffix rules catch the tail
    "restaurant": FOOD, "cafe": FOOD, "fast_food": FOOD, "ice_cream": FOOD,
    "bakery": FOOD, "pastry": FOOD, "food": FOOD, "food_court": FOOD,
    "seafood": FOOD, "chocolate": FOOD, "coffee": FOOD, "tea": FOOD,
    "bar": FOOD, "pub": FOOD, "nightclub": FOOD, "lounge": FOOD,
    "kitchen": FOOD, "deli": FOOD, "bbq": MAP_FURNITURE, "butcher": SHOPPING,
    "greengrocer": SHOPPING, "confectionery": SHOPPING, "cheese": SHOPPING,
    "pharmacy": HEALTHCARE, "hospital": HEALTHCARE, "clinic": HEALTHCARE,
    "doctor": HEALTHCARE, "doctors": HEALTHCARE, "dentist": HEALTHCARE,
    "veterinary": HEALTHCARE, "healthcare": HEALTHCARE, "health": HEALTHCARE,
    "physiotherapist": HEALTHCARE, "therapist": HEALTHCARE, "physician": HEALTHCARE,
    "medical_supply": HEALTHCARE, "nutrition_supplements": HEALTHCARE,
    "optometrist": HEALTHCARE, "optician": HEALTHCARE, "blood_bank": HEALTHCARE,
    "ambulance_station": GOVERNMENT, "vets": HEALTHCARE,
    "supermarket": SHOPPING, "convenience": SHOPPING, "mall": SHOPPING,
    "department_store": SHOPPING, "clothes": SHOPPING, "shoes": SHOPPING,
    "jewelry": SHOPPING, "jewellery": SHOPPING, "watches": SHOPPING,
    "furniture": SHOPPING, "houseware": SHOPPING, "electronics": SHOPPING,
    "computer": SHOPPING, "mobile_phone": SHOPPING, "books": SHOPPING,
    "toys": SHOPPING, "cosmetics": SHOPPING, "perfumery": SHOPPING,
    "stationery": SHOPPING, "hardware": SHOPPING, "doityourself": SHOPPING,
    "paint": SHOPPING, "electrical": SHOPPING, "lighting": SHOPPING,
    "curtain": SHOPPING, "fabric": SHOPPING, "carpet": SHOPPING,
    "bathroom_furnishing": SHOPPING, "bed": SHOPPING, "bag": SHOPPING,
    "leather": SHOPPING, "gift": SHOPPING, "florist": SHOPPING,
    "florist;chocolate": SHOPPING, "outdoor": SHOPPING, "sports_wear": SHOPPING,
    "sporting_goods": SHOPPING, "baby_goods": SHOPPING, "antiques": SHOPPING,
    "video_games": SHOPPING, "variety_store": SHOPPING, "general": SHOPPING,
    "kiosk": SHOPPING, "newsagent": SHOPPING, "tobacco": SHOPPING,
    "bookmaker": SHOPPING, "e-cigarette": SHOPPING, "alcohol": SHOPPING,
    "kitchen_supply": SHOPPING, "appliance": SHOPPING, "hifi": SHOPPING,
    "radiotechnics": SHOPPING, "dive_shop": SHOPPING, "skate_shop": SHOPPING,
    "photo": SERVICES, "photographer": SERVICES, "photo_studio": SERVICES,
    "copyshop": SERVICES, "printing": SERVICES, "laundry": SERVICES,
    "dry_cleaning": SERVICES, "tailor": SERVICES, "dressmaker": SERVICES,
    "upholsterer": SERVICES, "shoemaker": SERVICES, "key_cutter": SERVICES,
    "locksmith": SERVICES, "translator": SERVICES, "massage": SERVICES,
    "beauty": SERVICES, "hairdresser": SERVICES, "barber": SERVICES,
    "nail_salon": SERVICES, "spa": SERVICES, "tanning_salon": SERVICES,
    "pet": SERVICES, "pet_grooming": SERVICES, "animal_boarding": SERVICES,
    "animal_shelter": SERVICES, "animal_breeding": SERVICES,
    "car": SERVICES, "car_repair": SERVICES, "car_parts": SERVICES,
    "tyres": SERVICES, "car_wash": SERVICES, "car_rental": SERVICES,
    "motorcycle": SERVICES, "motorcycle_repair": SERVICES, "truck_repair": SERVICES,
    "taxi": TRANSPORT, "fuel": TRANSPORT, "charging_station": TRANSPORT,
    "vehicle_inspection": SERVICES, "car_rental_shop": SERVICES,
    "bicycle": SHOPPING, "bicycle_rental": SERVICES, "boat": SERVICES,
    "boat_rental": SERVICES, "swimming_pool": SPORT, "sports": SPORT,
    "sport": SPORT, "sports_centre": SPORT, "fitness_centre": SPORT,
    "gym": SPORT, "dance": SPORT, "martial_arts": SPORT, "tennis": SPORT,
    "golf": SPORT, "stadium": SPORT, "arena": SPORT, "pitch": SPORT,
    "playground": SPORT, "ski": SPORT, "dive_centre": SPORT,
    "scuba_diving": SPORT, "cycling": SPORT, "horse_riding": SPORT,
    "race_track": SPORT, "sports_hall": SPORT, "bowling": SPORT,
    "swimming": SPORT, "water_park": SPORT,
    "bank": FINANCE, "atm": FINANCE, "bureau_de_change": FINANCE,
    "money_transfer": FINANCE, "financial": FINANCE, "insurance": FINANCE,
    "insurance_agent": FINANCE,
    "hotel": LODGING, "motel": LODGING, "hostel": LODGING,
    "guest_house": LODGING, "bed_and_breakfast": LODGING, "chalet": LODGING,
    "hotel;attraction": LODGING, "aparthotel": LODGING,
    "school": EDUCATION, "kindergarten": EDUCATION, "college": EDUCATION,
    "university": EDUCATION, "educational_institution": EDUCATION,
    "library": EDUCATION, "driving_school": EDUCATION, "language_school": EDUCATION,
    "music_school": EDUCATION, "childcare": EDUCATION, "nursery": EDUCATION,
    "preschool": EDUCATION, "childcare_centre": EDUCATION,
    "place_of_worship": RELIGIOUS, "mosque": RELIGIOUS, "church": RELIGIOUS,
    "grave_yard": RELIGIOUS, "cemetery": RELIGIOUS, "chapel": RELIGIOUS,
    "synagogue": RELIGIOUS, "temple": RELIGIOUS, "shrine": RELIGIOUS,
    "attraction": ATTRACTION, "museum": ATTRACTION, "artwork": MAP_FURNITURE,
    "ruins": ATTRACTION, "castle": ATTRACTION, "fort": ATTRACTION,
    "monument": ATTRACTION, "memorial": ATTRACTION, "archaeological_site": ATTRACTION,
    "theme_park": ATTRACTION, "zoo": ATTRACTION, "gallery": ATTRACTION,
    "theatre": ATTRACTION, "cinema": ATTRACTION, "viewpoint": ATTRACTION,
    "planetarium": ATTRACTION, "waterfall": ATTRACTION, "battlefield": ATTRACTION,
    "park": ATTRACTION, "garden": ATTRACTION,
    "parking": PARKING, "parking_entrance": MAP_FURNITURE,
    "parking_position": MAP_FURNITURE, "parking_space": MAP_FURNITURE,
    "bicycle_parking": MAP_FURNITURE, "motorcycle_parking": MAP_FURNITURE,
    "government": GOVERNMENT, "public_building": MAP_FURNITURE,
    "townhall": GOVERNMENT, "courthouse": GOVERNMENT, "prison": GOVERNMENT,
    "police": GOVERNMENT, "fire_station": GOVERNMENT, "post_office": GOVERNMENT,
    "diplomatic": GOVERNMENT, "embassy": GOVERNMENT, "consulate": GOVERNMENT,
    "post_box": MAP_FURNITURE, "telephone": MAP_FURNITURE,
    "shelter": MAP_FURNITURE, "bench": MAP_FURNITURE, "gate": MAP_FURNITURE,
    "toilets": MAP_FURNITURE, "shower": MAP_FURNITURE, "fountain": MAP_FURNITURE,
    "drinking_water": MAP_FURNITURE, "watering_place": MAP_FURNITURE,
    "waste_basket": MAP_FURNITURE, "waste_disposal": MAP_FURNITURE,
    "bbq_grate": MAP_FURNITURE, "clock": MAP_FURNITURE, "smoking_area": MAP_FURNITURE,
    "weighbridge": MAP_FURNITURE, "designated": MAP_FURNITURE,
    "surveillance": MAP_FURNITURE, "information": MAP_FURNITURE,
    "picnic_site": MAP_FURNITURE, "camp_pitch": MAP_FURNITURE,
    "level_crossing": MAP_FURNITURE, "tram_level_crossing": MAP_FURNITURE,
    "tram_crossing": MAP_FURNITURE, "crossing": MAP_FURNITURE,
    "holding_position": MAP_FURNITURE, "stop_position": MAP_FURNITURE,
    "subway_entrance": MAP_FURNITURE, "bus_stop": TRANSPORT,
    "bus_station": TRANSPORT, "terminal": TRANSPORT, "station": TRANSPORT,
    "ferry_terminal": TRANSPORT, "helipad": TRANSPORT, "aerodrome": TRANSPORT,
    "airport": TRANSPORT, "heliport": TRANSPORT, "slipway": MAP_FURNITURE,
    "company": OFFICE, "office": OFFICE, "estate_agent": OFFICE,
    "real_estate": OFFICE, "insurance_office": OFFICE, "lawyer": OFFICE,
    "legal": OFFICE, "accountant": OFFICE, "notary": OFFICE,
    "consultant": OFFICE, "consulting": OFFICE, "engineering": OFFICE,
    "architect": OFFICE, "contractor": OFFICE, "construction_company": INDUSTRY,
    "trade": INDUSTRY, "wholesale": INDUSTRY, "logistics": INDUSTRY,
    "moving_company": INDUSTRY, "storage": INDUSTRY, "warehouse": INDUSTRY,
    "craft": INDUSTRY, "carpenter": INDUSTRY, "electrician": INDUSTRY,
    "plumbing": INDUSTRY, "hvac": INDUSTRY, "metal": INDUSTRY,
    "metal_construction": INDUSTRY, "machine_shop": INDUSTRY, "furnace": INDUSTRY,
    "agricultural_engines": INDUSTRY, "water_utility": INFRASTRUCTURE,
    "waste_transfer_station": INFRASTRUCTURE, "recycling": INFRASTRUCTURE,
    "apartment": RESIDENTIAL, "flats": RESIDENTIAL, "house": RESIDENTIAL,
    "villa": RESIDENTIAL, "residential": RESIDENTIAL,
    "accommodation": RESIDENTIAL, "workers_housing": RESIDENTIAL,
    "company_housing": RESIDENTIAL, "staff_housing": RESIDENTIAL,
    "internet_cafe": SERVICES, "coworking": OFFICE, "employment_agency": OFFICE,
    "it": OFFICE, "telecommunication": OFFICE, "advertising_agency": OFFICE,
    "travel_agency": SERVICES, "tourist_information": SERVICES,
    "marketplace": SHOPPING, "public_bath": SERVICES, "sauna": SPORT,
    "social_facility": SERVICES, "community_centre": SERVICES,
    "events_venue": SERVICES, "arts_centre": ATTRACTION,
    "conference_centre": SERVICES, "nightclub": FOOD, "casino": ATTRACTION,
    "archaeological": ATTRACTION, "art": ATTRACTION, "marine": TRANSPORT,
    "crane": INDUSTRY, "gren": SERVICES, "union": OFFICE, "ong": OFFICE,
    "ngo": OFFICE, "charity": SERVICES, "foundation": OFFICE,
    "anthropology": ATTRACTION, "observatory": ATTRACTION,
}

#: Overture suffix rules (exact match wins first).
_OVERTURE_SUFFIX = (
    ("_restaurant", FOOD),
    ("_cuisine", FOOD),
)

#: OSM suffix rules (exact match wins first).
_OSM_SUFFIX = (
    ("_restaurant", FOOD),
    ("_crossing", MAP_FURNITURE),
    ("_store", SHOPPING),
    ("_shop", SHOPPING),
    ("_school", EDUCATION),
    ("_center", SERVICES),
    ("_centre", SERVICES),
    ("_station", TRANSPORT),
    ("_office", OFFICE),
    ("_company", OFFICE),
    ("_supplier", INDUSTRY),
    ("_service", SERVICES),
)


def _lookup(value: "str | None", table: dict, suffixes) -> str:
    if not value:
        return UNKNOWN
    v = value.strip().casefold()
    if v in table:
        return table[v]
    for suffix, family in suffixes:
        if v.endswith(suffix):
            return family
    return UNKNOWN


#: Words that mark a `landmark_and_historical_building` record as residential
#: junk rather than a landmark (195 of 583 measured carry one).
_LANDMARK_RESIDENTIAL_HINT = (
    "tower", "bldg", "building", "compound", "village", "residence",
    "flat", "villa", "home", "apartment", "oasis", "city, phase", "phase",
    "street ", "block ", "villas",
)

#: Words that confirm a record really is a landmark.
_LANDMARK_ATTRACTION_HINT = (
    "park", "museum", "stadium", "mosque", "church", "temple", "fort",
    "castle", "palace", "souq", "corniche", "zoo", "gallery", "theatre",
    "theater", "opera", "library", "university", "heritage", "historic",
    "old ", "tower of", "club", "garden", "beach", "port", "harbor",
    "harbour", "quay", "market",
)


def _landmark_family(name: "str | None") -> str:
    """The `landmark_and_historical_building` bucket is resolved by NAME."""
    n = (name or "").strip().casefold()
    if not n:
        return UNKNOWN
    for hint in _LANDMARK_RESIDENTIAL_HINT:
        if hint in n:
            return RESIDENTIAL
    for hint in _LANDMARK_ATTRACTION_HINT:
        if hint in n:
            return ATTRACTION
    return UNKNOWN


#: Words in a NAME that say what a record is with more authority than its
#: category does.
#:
#: Overture filed "QNB ATM Ministry of Commerce & Industry Lusail" under
#: ``cabin`` — a LODGING category worth 0.92 usefulness — which is how a cash
#: machine came to outscore the ministry it stands inside and take the row
#: above it in a driver's search results. A bank ATM is not a cabin and it is
#: not lodging, and the record says so in its own name.
#:
#: Matched as a WHOLE WORD against the normalized name, never as a substring:
#: "atm" is inside "Fatma", "Atmosphere" and dozens of transliterations, and a
#: substring rule would refile all of them as cash machines.
_ATM_WORDS = frozenset({"atm", "atms"})

#: The category written onto a record whose name says ATM but whose source
#: category says something unrelated. It is a real Overture/OSM vocabulary
#: value, so every consumer that already reads `category` keeps working — and
#: the driver sees "atm" rather than "cabin".
ATM_CATEGORY = "atm"


def _name_words(name: "str | None") -> frozenset:
    """Lowercased alphanumeric words of a name, for whole-word name rules."""
    if not name:
        return frozenset()
    out, cur = [], []
    for ch in name.casefold():
        if ch.isalnum():
            cur.append(ch)
        elif cur:
            out.append("".join(cur))
            cur = []
    if cur:
        out.append("".join(cur))
    return frozenset(out)


def name_says_atm(name: "str | None") -> bool:
    """Does this record's own name say it is a cash machine?"""
    return bool(_ATM_WORDS & _name_words(name))


def _apply_name_override(family: str, name: "str | None") -> str:
    """Let an unambiguous NAME correct a category that contradicts it.

    Deliberately one rule, not a second classifier: the bar for adding another
    is the one this file already sets — a measured, whole-word, unambiguous
    marker whose family the source category demonstrably gets wrong.
    """
    if family != FINANCE and name_says_atm(name):
        return FINANCE
    return family


def refine_category(category: "str | None", name: "str | None") -> "str | None":
    """The category a record should CARRY, once its name has been read.

    Returns the source category unchanged unless a name override fired, in
    which case the record is relabelled to the thing it actually is. Never
    invents a category for a record that had none and says nothing.
    """
    if not name_says_atm(name):
        return category
    if _lookup(category, _OVERTURE_FAMILY, _OVERTURE_SUFFIX) == FINANCE:
        return category      # already filed as finance ("atm", "bank"): keep it
    if _lookup(category, _OSM_KEYS, _OSM_SUFFIX) == FINANCE:
        return category
    return ATM_CATEGORY


def classify_overture(category: "str | None", name: "str | None" = None) -> str:
    """Map an Overture ``category`` to a semantic family."""
    if category and category.strip().casefold() == "landmark_and_historical_building":
        return _apply_name_override(_landmark_family(name), name)
    return _apply_name_override(
        _lookup(category, _OVERTURE_FAMILY, _OVERTURE_SUFFIX), name)


def classify_osm(poi_class: "str | None", name: "str | None" = None) -> str:
    """Map an OSM ``poi_class`` (or absence of one) to a semantic family."""
    if not poi_class or poi_class.strip().casefold() in ("yes", "<none>"):
        # Named OSM buildings carry no class. They are real named places
        # (Tornado Tower, Al Fardan Centre) but the name is all the evidence
        # there is, so they rank as UNKNOWN — retained, never deleted.
        return _apply_name_override(UNKNOWN, name)
    return _apply_name_override(_lookup(poi_class, _OSM_KEYS, _OSM_SUFFIX), name)


def family_name(family: str) -> str:
    return family


def families_compatible(a_family: str, b_family: str) -> bool:
    """May two same-name records describe the same place?

    Equal families match. An UNKNOWN record matches anything (it carries no
    contradicting evidence). Two KEPT families of the same kind match
    (Overture ``restaurant`` vs OSM ``restaurant``). Deliberately NOT a
    transitive relation — it is only ever asked of a concrete pair.
    """
    if a_family == b_family:
        return True
    if UNKNOWN in (a_family, b_family):
        return True
    both_kept = {a_family, b_family} <= {
        FOOD, SHOPPING, HEALTHCARE, SERVICES, FINANCE, TRANSPORT, LODGING,
    }
    return both_kept


# Assemble the Overture table from the sets above.
_OVERTURE_FAMILY: dict = {}
for _family, _members in (
    (FOOD, _FOOD), (HEALTHCARE, _HEALTHCARE), (SHOPPING, _SHOPPING),
    (TRANSPORT, _TRANSPORT), (GOVERNMENT, _GOVERNMENT), (LODGING, _LODGING),
    (ATTRACTION, _ATTRACTION), (SPORT, _SPORT), (PARKING, _PARKING),
    (FINANCE, _FINANCE), (SERVICES, _SERVICES), (RESIDENTIAL, _RESIDENTIAL),
    (OFFICE, _OFFICE), (INDUSTRY, _INDUSTRY), (MAP_FURNITURE, _MAP_FURNITURE),
    (INFRASTRUCTURE, _INFRASTRUCTURE), (EDUCATION, _EDUCATION),
    (RELIGIOUS, _RELIGIOUS),
):
    for _cat in _members:
        _OVERTURE_FAMILY[_cat.strip().casefold()] = _family