endpoint GetPet GET /api/pets/{id: String} -> {
  200 -> PetResponse
}

type PetResponse {
  id: String,
  name: String
}
