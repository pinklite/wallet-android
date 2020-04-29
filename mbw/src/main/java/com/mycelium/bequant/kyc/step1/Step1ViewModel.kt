package com.mycelium.bequant.kyc.step1

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.mycelium.bequant.remote.model.KYCRequest


class Step1ViewModel : ViewModel() {
    val firstName = MutableLiveData<String>()
    val lastName = MutableLiveData<String>()
    val birthday = MutableLiveData<String>()
    val nationality = MutableLiveData<String>()

    fun fillModel(kyc: KYCRequest) {
        kyc.first_name = firstName.value
        kyc.last_name = lastName.value
        kyc.birthday = birthday.value
        kyc.nationality = nationality.value
    }
}