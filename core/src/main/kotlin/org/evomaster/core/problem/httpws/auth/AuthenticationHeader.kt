package org.evomaster.core.problem.httpws.auth

//should be immutable

class AuthenticationHeader(val name: String, var value: String) {

    init {
        if(name.isBlank()){
            throw IllegalArgumentException("Blank name")
        }
        //can values be blank? maybe...
    }
}