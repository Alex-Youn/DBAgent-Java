function Ask-Ollama {
    param (
        [Parameter(Mandatory=$true)]
        [string]$Prompt,
        [string]$Model = "gemma4:31b-cloud"
    )

    $payload = @{
        model = $Model
        messages = @(
            @{ role = "user"; content = $Prompt }
        )
        stream = $false
    } | ConvertTo-Json -Depth 5

    $bytes = [System.Text.Encoding]::UTF8.GetBytes($payload)

    try {
        # WebRequest로 원시 스트림을 받아 UTF-8로 직접 디코딩
        $req = [System.Net.HttpWebRequest]::Create("http://localhost:11434/api/chat")
        $req.Method = "POST"
        $req.ContentType = "application/json; charset=utf-8"
        $req.ContentLength = $bytes.Length

        $requestStream = $req.GetRequestStream()
        $requestStream.Write($bytes, 0, $bytes.Length)
        $requestStream.Close()

        $resp = $req.GetResponse()
        $reader = New-Object System.IO.StreamReader($resp.GetResponseStream(), [System.Text.Encoding]::UTF8)
        $rawJson = $reader.ReadToEnd()
        $reader.Close()
        $resp.Close()

        $jsonObj = $rawJson | ConvertFrom-Json
        return $jsonObj.message.content
    }
    catch {
        Write-Error "Ollama 호출 실패: $_"
    }
}